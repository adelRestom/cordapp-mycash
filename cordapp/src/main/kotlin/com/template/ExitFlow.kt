package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.state.MyCash
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object ExitFlow {

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
            object GENERATING_TRANSACTION : Step("Generating transaction.")
            object SIGN_FINALIZE : Step("Signing transaction and finalizing state.") {
                override fun childProgressTracker() = SignFinalize.Initiator.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    SIGN_FINALIZE
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
            // Obtain a reference to the notary we want to use
            require(myCashInputs.map { it.state.notary }.distinct().size == 1) { "Notary must be identical across all inputs" }
            val notary = myCashInputs[0].state.notary

            // Generate an unsigned transaction.
            val requiredSigs = myCashInputs.flatMap { it.state.data.exitKeys }.distinct()
            val txCommand = Command(MyCashContract.Commands.Exit(), requiredSigs)
            val txBuilder = TransactionBuilder(notary)
                    .addCommand(txCommand)
            myCashInputs.forEach { txBuilder.addInputState(it) }

            // Stage 2.
            progressTracker.currentStep = SIGN_FINALIZE
            // Signing transaction and finalizing state
            return subFlow(SignFinalize.Initiator(txBuilder = txBuilder, progressTracker = SIGN_FINALIZE.childProgressTracker()))
        }
    }
}
