package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.state.MyCash
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
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
            object GENERATING_TRANSACTION : Step("Generating transaction based on MyCash EXIT flow.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
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
            val myCashStateAndRef = serviceHub.toStateAndRef<MyCash>(stateRef)
            // Obtain a reference to the notary we want to use.
            val notary = myCashStateAndRef.state.notary;
            val issuer = myCashStateAndRef.state.data.issuer
            val owner = myCashStateAndRef.state.data.owner
            require(ourIdentity == issuer || ourIdentity == owner) {
                "$ourIdentity is neither the issuer: $issuer nor the owner: $owner"
            }
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
            val fullySignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }
}
