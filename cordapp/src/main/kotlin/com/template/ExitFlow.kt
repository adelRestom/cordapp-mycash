package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.template.MyCash
import com.template.MyCashContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object ExitFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val stateRef: StateRef) : FlowLogic<SignedTransaction>() {

        constructor(txSecurehash: SecureHash, txIndex: Int) : this(StateRef(txSecurehash, txIndex))
        constructor(txHash: String, txIndex: Int) : this(SecureHash.parse(txHash), txIndex)

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on destroying MyCash.")
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

            val myCashStateAndRef = serviceHub.toStateAndRef<MyCash>(stateRef)
            // Obtain a reference to the notary we want to use.
            val notary = myCashStateAndRef.state.notary;
            val issuer = myCashStateAndRef.state.data.issuer
            val owner = myCashStateAndRef.state.data.owner

            require(ourIdentity == issuer || ourIdentity == owner) {
                "$ourIdentity is neither the issue: $issuer or the owner: $owner"
            }

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val txCommand = Command(MyCashContract.Commands.Exit(), myCashStateAndRef.state.data.exitKeys.map { it })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(myCashStateAndRef)
                    .addCommand(txCommand)

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
            val counterParty = myCashStateAndRef.state.data.exitKeys.minus(ourIdentity.owningKey).single()
            val otherPartyFlow = initiateFlow(serviceHub.identityService.partyFromKey(counterParty)!!)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val input = serviceHub.loadStates(stx.tx.inputs.toSet()).map { it.state.data }.get(0)
                    "This must be a MyCash transaction." using (input is MyCash)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
