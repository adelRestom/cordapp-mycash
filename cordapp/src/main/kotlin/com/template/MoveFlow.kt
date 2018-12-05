package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.template.MyCash
import com.template.MyCashContract
import com.template.MyCashContract.Companion.MyCash_Contract_ID
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.util.*

object MoveFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val owner: Party, val newOwner: Party, val dollarCents: Long) : FlowLogic<SignedTransaction>() {
        
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object FETCHING_INPUTS : Step("Fetching owner's input states.")
            object GENERATING_TRANSACTION : Step("Generating transaction based on move MyCash command.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    FETCHING_INPUTS,
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

            require(dollarCents > 0L) { "Move amount must be greater than zero" }

            // Stage 0.
            progressTracker.currentStep = FETCHING_INPUTS
            val ownerUnconsumedCriteria = QueryCriteria.FungibleAssetQueryCriteria(owner = listOf(owner), status = Vault.StateStatus.UNCONSUMED)
            val ownerUnconsumedInputs = serviceHub.vaultService.queryBy<MyCash>(ownerUnconsumedCriteria).states
            var inputSum = 0L
            var inputs = mutableListOf<StateAndRef<MyCash>>()
            for (input in ownerUnconsumedInputs){
                inputSum += input.state.data.amount.quantity
                inputs.add(input)
                // Gather enough cash
                if (inputSum > dollarCents) {
                    break
                }
            }

            require(inputSum >= dollarCents) { "$owner doesn't have enough cash! Only $inputSum is found" }

            val change = inputSum - dollarCents

            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val issuer = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name("Bank", "New York", "US"))
            val bank = issuer as Party
                
            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val txCommand = Command(MyCashContract.Commands.Move(), listOf(owner.owningKey, newOwner.owningKey))
            val txBuilder = TransactionBuilder(notary)
                    .addCommand(txCommand)

            // Add inputs
            for (input in inputs) {
                txBuilder.addInputState(input)
            }

            // Add outputs
            val movedAmount = Amount(dollarCents, Issued(bank.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))
            val newCashState = MyCash(issuer = bank, owner = newOwner, amount = movedAmount)
            txBuilder.addOutputState(newCashState, MyCash_Contract_ID)
            if (change > 0) {
                val changeAmount = Amount(change, Issued(bank.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))
                val changeCashState = MyCash(issuer = bank, owner = owner, amount = changeAmount)
                txBuilder.addOutputState(changeCashState, MyCash_Contract_ID)
            }

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
            val newOwnerFlow = initiateFlow(newOwner)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(newOwnerFlow), GATHERING_SIGS.childProgressTracker()))

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
                    // Fetch only my output
                    val output = stx.tx.outputs.filter {
                        val myCash = it.data as MyCash
                        myCash.owner == ownerFlow.counterparty
                    }.single().data
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
