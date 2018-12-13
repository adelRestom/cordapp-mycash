package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.contract.MyCashContract.Companion.MyCash_Contract_ID
import com.template.schema.MyCashSchemaV1
import com.template.state.MyCash
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.core.utilities.unwrap
import java.util.*

object MoveFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val moveAmounts: List<MyCash>, val newOwner: Party) : FlowLogic<SignedTransaction>() {

        init {
            require(moveAmounts.filter { it.amount.quantity <= 0 }.isEmpty()) { "Move amount must be greater than zero" }
        }

        constructor(issuer: Party, owner: Party, amount: Long, currencyCode: String, newOwner: Party):
                this(listOf(MyCash(issuer, owner, amount, currencyCode)), newOwner)

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object FETCHING_INPUTS : Step("Fetching owner's input states.")
            object GENERATING_TRANSACTION : Step("Generating transaction based on MyCash MOVE flow.")
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
            // Stage 0.
            progressTracker.currentStep = FETCHING_INPUTS

            var inputs = mutableListOf<StateAndRef<MyCash>>()
            var changeAmounts = mutableListOf<MyCash>()
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("BEFORE: inputs.size: ${inputs.size}\n changeAmunts.size: ${changeAmounts.size}")
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            for (moveAmount in moveAmounts) {
                val counterParty = initiateFlow(moveAmount.participants.first())
                val untrustedData = counterParty.sendAndReceive<MoveData>(MoveData(moveAmount, inputs, changeAmounts))
                val moveData = untrustedData.unwrap {
                    it as? MoveData ?: throw Exception("MOVE Initiator flow cannot parse the received data")
                }
                inputs = moveData.inputs.toMutableList()
                changeAmounts = moveData.changeAmounts.toMutableList()
            }
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("AFTER: inputs.size: ${inputs.size}\n changeAmunts.size: ${changeAmounts.size}")
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")

            // Stage 1.
            // Obtain a reference to the notary we want to use.
            require (inputs.map { it.state.notary }.distinct().size == 1) { "Notary must be identical across all inputs" }
            val notary = inputs[0].state.notary

            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val requiredParties = inputs.map { it.state.data.owner }.distinct().plus(newOwner)
            val txCommand = Command(MyCashContract.Commands.Move(), requiredParties.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addCommand(txCommand)

            // Add inputs
            for (input in inputs) {
                txBuilder.addInputState(input)
            }

            // Add outputs
            for (moveAmount in moveAmounts) {
                txBuilder.addOutputState(moveAmount.copy(owner = newOwner), MyCash_Contract_ID)
            }
            for (changeAmount in changeAmounts) {
                txBuilder.addOutputState(changeAmount, MyCash_Contract_ID)
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
            val counterParties = requiredParties.minus(ourIdentity).map { initiateFlow(it) }
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, counterParties, GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterFlow: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val untrustedData = counterFlow.receive<MoveData>()
            val moveData = untrustedData.unwrap {
                it as? MoveData ?: throw Exception("MOVE Acceptor flow cannot parse the received data")
            }
            val inputs = moveData.inputs.toMutableList()
            val changeAmounts = moveData.changeAmounts.toMutableList()
            val issuer = moveData.moveAmount.issuer
            val owner = moveData.moveAmount.owner
            val amount = moveData.moveAmount.amount.quantity
            val currencyCode = moveData.moveAmount.amount.token.product.currencyCode
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("MoveData: issuer: $issuer\n owner: $owner\n amount: $amount\n currencyCode: $currencyCode\n ourIdentitiy: $ourIdentity")
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")

            val myCashCriteria = QueryCriteria.FungibleAssetQueryCriteria(issuer = listOf(issuer),
                    owner = listOf(owner),
                    status = Vault.StateStatus.UNCONSUMED
            )

            val unconsumedInputs = builder {
                val currencyIndex = MyCashSchemaV1.PersistentMyCash::currencyCode.equal(currencyCode)
                val currencyCriteria = QueryCriteria.VaultCustomQueryCriteria(currencyIndex)
                val criteria = myCashCriteria.and(currencyCriteria)
                serviceHub.vaultService.queryBy<MyCash>(criteria).states
            }

            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("SIZE: "+unconsumedInputs.size)
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")

            var inputSum = 0L

            for (input in unconsumedInputs){
                // The below condition is to avoid double spending in case the flow initiator passed more than one move amount of the same issuer/owner/currency code
                if (!inputs.contains(input)) {
                    inputSum += input.state.data.amount.quantity
                    inputs.add(input)
                }
                // Gather enough cash
                if (inputSum > amount) {
                    break
                }
            }

            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("inputSum: $inputSum\n amount: $amount")
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")

            require(inputSum >= amount) { "$owner doesn't have enough cash! Only $inputSum was found for move amount: {Issuer: $issuer, Owner: $owner, Amount: $amount, Currency Code: $currencyCode}" }

            val change = inputSum - amount
            if (change > 0) {
                val changeAmount = MyCash(owner, Amount(change, Issued(issuer.ref(OpaqueBytes.of(0x01)), Currency.getInstance(currencyCode))))
                changeAmounts.add(changeAmount)
            }

            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("INSIDE: inputs.size: ${inputs.size}\n changeAmunts.size: ${changeAmounts.size}")
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")

            counterFlow.send(moveData.copy(inputs = inputs, changeAmounts = changeAmounts))
        }
    }

    @CordaSerializable
    data class MoveData (val moveAmount: MyCash, val inputs: MutableList<StateAndRef<MyCash>>, val changeAmounts: MutableList<MyCash>)



    /*@InitiatedBy(Initiator::class)
    class Acceptor(val counterFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val myCashOutputs = stx.tx.outputs.filter {
                        val myCash = it.data as? MyCash
                        if (myCash != null)
                            myCash.owner == counterFlow.counterparty
                        else
                            false
                    }
                    "This must be a MyCash transaction." using (myCashOutputs.isNotEmpty())
                }
            }
            return subFlow(signTransactionFlow)
        }
    }*/
}
