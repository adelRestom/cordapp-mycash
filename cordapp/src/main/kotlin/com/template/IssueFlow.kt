package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.MyCashContract.Companion.MyCash_Contract_ID
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.Issued
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.util.*

object IssueFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val owner: Party, val dollarCents: Long) : FlowLogic<SignedTransaction>() {
        
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on a new MyCash state.")
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
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val amount = Amount(dollarCents, Issued(ourIdentity.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))
            val issuer = ourIdentity
                
            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val myCashState = MyCash(issuer = issuer, owner = owner, amount = amount)
            val txCommand = Command(MyCashContract.Commands.Issue(), listOf(issuer.owningKey, owner.owningKey))
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(myCashState, MyCash_Contract_ID)
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
            val ownerFlow = initiateFlow(owner)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(ownerFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val ownerFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(ownerFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be a MyCash transaction." using (output is MyCash)
                    val myCashState = output as MyCash
                    "I will only accept USD." using (myCashState.amount.token.product.currencyCode.equals("USD"))
                    "I will only accept cash issued by {O=Bank,L=New York,C=US}." using (myCashState.issuer.nameOrNull().toString().equals("O=Bank, L=New York, C=US"))
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
