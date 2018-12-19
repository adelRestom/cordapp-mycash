package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.contract.MyCashContract.Companion.MyCash_Contract_ID
import com.template.state.MyCash
import net.corda.core.contracts.Command
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object IssueFlow {
    @StartableByRPC
    class Initiator(val outputs: List<MyCash>) : FlowLogic<SignedTransaction>() {

        constructor(issuer: Party,
                    owner: Party,
                    amount: Long,
                    currencyCode: String):
                this(listOf(MyCash(issuer, owner, amount, currencyCode)))

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
            // Obtain a reference to the notary we want to use
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Generate an unsigned transaction.
            val requiredParties = outputs.flatMap{ listOf(it.issuer, it.owner) }.distinct()
            val txCommand = Command(MyCashContract.Commands.Issue(), requiredParties.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addCommand(txCommand)
            outputs.forEach { txBuilder.addOutputState(it, MyCash_Contract_ID) }

            // Stage 2.
            progressTracker.currentStep = SIGN_FINALIZE
            // Signing transaction and finalizing state
            return subFlow(SignFinalize.Initiator(txBuilder, progressTracker = SIGN_FINALIZE.childProgressTracker()))
        }
    }
}
