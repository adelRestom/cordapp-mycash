package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.state.MyCash
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object ExitFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val inputs: List<StateRef>) : FlowLogic<SignedTransaction>() {

        init {
            require(inputs.isNotEmpty()) { "Inputs list cannot be empty" }
        }

        constructor(txSecurehash: SecureHash, txIndex: Int) : this(listOf(StateRef(txSecurehash, txIndex)))
        constructor(txHash: String, txIndex: Int) : this(SecureHash.parse(txHash), txIndex)

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on MyCash EXIT flow.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            val myCashInputs: List<StateAndRef<MyCash>> = try {
                inputs.map { serviceHub.toStateAndRef<MyCash>(it) }
            }
            catch (e: TransactionResolutionException) {
                throw FlowException("$ourIdentity cannot EXIT one of the passed MyCash states, because it wasn't one of the participants at the time of their ISSUE")
            }
            require(myCashInputs.map { it.state.notary }.distinct().size == 1) { "Notary must be identical across all inputs" }

            // Obtain a reference to the notary we want to use.
            val notary = myCashInputs[0].state.notary;
            // Generate an unsigned transaction.
            val requiredSigs = myCashInputs.flatMap { it.state.data.exitKeys }.distinct()
            val txCommand = Command(MyCashContract.Commands.Exit(), requiredSigs)
            val txBuilder = TransactionBuilder(notary)
                    .addCommand(txCommand)

            myCashInputs.forEach { txBuilder.addInputState(it) }

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val requiredParties = requiredSigs.minus(ourIdentity.owningKey).map {
                serviceHub.identityService.requireWellKnownPartyFromAnonymous(AnonymousParty(it))
            }
            val counterParties = requiredParties.map { initiateFlow(it!!) }
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, counterParties, GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val inputs = serviceHub.loadStates(stx.tx.inputs.toSet()).map { it.state.data }
                    val myCashInputs = inputs.filter {
                        it is MyCash && it.exitKeys.contains(ourIdentity.owningKey)
                    }
                    "This must be a MyCash transaction." using (myCashInputs.isNotEmpty())
                }
            }
            return subFlow(signTransactionFlow)
        }
    }
}
