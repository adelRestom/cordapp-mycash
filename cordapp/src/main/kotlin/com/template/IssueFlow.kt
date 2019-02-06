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
    class Initiator(val outputs: List<MyCash>, val anonymous: Boolean = false) : FlowLogic<SignedTransaction>() {

        constructor(issuer: Party, owner: Party, amount: Long, currencyCode: String, anonymous: Boolean = false):
                this(listOf(MyCash(issuer, owner, amount, currencyCode)), anonymous)

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction.")
            object GENERATE_CONFIDENTIAL_STATES : Step("Generating confidential states.") {
                override fun childProgressTracker() = AnonymizeFlow.EncryptStates.tracker()
            }
            object GENERATE_CONFIDENTIAL_IDS : Step("Generating confidential identities for the transaction.")
            object SIGN_FINALIZE : Step("Signing transaction and finalizing state.") {
                override fun childProgressTracker() = SignFinalize.Initiator.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    GENERATE_CONFIDENTIAL_STATES,
                    GENERATE_CONFIDENTIAL_IDS,
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
            val txBuilder = TransactionBuilder(notary)
            val txCommand: Command<MyCashContract.Commands.Issue>
            val requiredParties = outputs.flatMap{ listOf(it.issuer, it.owner) }.distinct()

            if (anonymous) {
                progressTracker.currentStep = GENERATE_CONFIDENTIAL_STATES
                val anonymousStates = subFlow(AnonymizeFlow.EncryptStates(
                        mapOf(), outputs,
                        GENERATE_CONFIDENTIAL_STATES.childProgressTracker()))
                // Add output states with anonymous issuers and owners
                anonymousStates.first.forEach {
                    txBuilder.addOutputState(it.toState(), MyCash_Contract_ID)
                }

                progressTracker.currentStep = GENERATE_CONFIDENTIAL_IDS
                // Required signers = anonymous issuers and owners + anonymous me
                val anonymousParties = listOf(anonymousStates.first.flatMap { listOf(it.issuer, it.owner) },
                                                        anonymousStates.third.map { it }).flatten()
                // Anonymous parties are required to sign the transaction
                txCommand = Command(MyCashContract.Commands.Issue(), anonymousParties.map { it.owningKey })
            }
            else {
                txCommand = Command(MyCashContract.Commands.Issue(), requiredParties.map { it.owningKey })
                outputs.forEach {
                    txBuilder.addOutputState(it, MyCash_Contract_ID)
                }
            }
            txBuilder.addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = SIGN_FINALIZE
            // Signing transaction and finalizing state
            return subFlow(SignFinalize.Initiator(txBuilder, progressTracker = SIGN_FINALIZE.childProgressTracker()))
        }
    }
}
