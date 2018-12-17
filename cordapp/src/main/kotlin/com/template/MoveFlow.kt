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

    // This class is used to send data out (from Initiator to Acceptor)
    @CordaSerializable
    data class MoveDataOut (val moveAmounts: List<MyCash>)

    // This class is used to receive data in (to Initiator from Acceptor)
    @CordaSerializable
    data class MoveDataIn (val utxoList: MutableList<StateAndRef<MyCash>>, val changeAmounts: MutableList<MyCash>)

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val moveAmounts: List<MyCash>, val newOwner: Party) : FlowLogic<SignedTransaction>() {

        init {
            require(moveAmounts.filter { it.amount.quantity <= 0 }.isEmpty()) { "Move amount must be greater than zero" }
        }

        // Constructor to move one amount
        constructor(issuer: Party, owner: Party, amount: Long, currencyCode: String, newOwner: Party):
                this(listOf(MyCash(issuer, owner, amount, currencyCode)), newOwner)

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object CONSOLIDATE_INPUTS : Step("Consolidate inputs by owner/issuer/currency code.")
            object FETCHING_INPUTS : Step("Fetching owners' input states.") {
                override fun childProgressTracker() = Acceptor.tracker()
            }
            object GENERATING_TRANSACTION : Step("Generating transaction based on MyCash MOVE flow.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparties' signatures.") {
                override fun childProgressTracker() = MoveSigsInitiator.tracker()
            }
            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    CONSOLIDATE_INPUTS,
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
            // Stage 1.
            progressTracker.currentStep = CONSOLIDATE_INPUTS
            // We will consolidate move amounts by owner/issuer/currency code to minimize
            // the number of trips to owners' vaults
            val consolidatedMoveAmounts = mutableListOf<MyCash>()
            if (moveAmounts.size > 1) {
                val grpByOwner = moveAmounts.groupBy { it.owner }
                for (byOwner in grpByOwner.values) {
                    // *****BY OWNER: START*****"
                    val grpByIssuer = byOwner.groupBy { it.issuer }
                    for (byIssuer in grpByIssuer.values) {
                        // *****BY ISSUER: START*****"
                        val grpByCurrency = byIssuer.groupBy { it.amount.token.product.currencyCode }
                        for (byCurrency in grpByCurrency.values) {
                            // *****BY CURRENCY: START*****"
                            var sum = 0L
                            byCurrency.forEach { sum += it.amount.quantity }
                            val totalMoveAmount = byCurrency[0].copy(amount = byCurrency[0].amount.copy(quantity = sum))
                            consolidatedMoveAmounts.add(totalMoveAmount)
                            // *****BY CURRENCY: END*****"
                        }
                        // *****BY ISSUER: START*****"
                    }
                    // "*****BY OWNER: END*****"
                }
            }
            else
                consolidatedMoveAmounts.add(moveAmounts.first())

            // After consolidation; we will group the results by owner so we can visit each owner's vault only once to
            // gather all the required UTXO to cover the consolidated move amounts
            val consolidatedGroupedByOwner = consolidatedMoveAmounts.groupBy { it.owner }


            // Stage 2.
            progressTracker.currentStep = FETCHING_INPUTS
            // This list will hold all the UTXO that we can use to fulfill the move amounts
            val inputs = mutableListOf<StateAndRef<MyCash>>()
            // This list will hold new outputs to return change to owners
            val changeAmounts = mutableListOf<MyCash>()
            // Query owners' vaults to gather UTXO's
            for (moveAmount in consolidatedGroupedByOwner) {
                // Our key is the owner
                val counterParty = initiateFlow(moveAmount.key)
                val untrustedData = counterParty.sendAndReceive<MoveDataIn>(MoveDataOut(moveAmount.value))
                val moveData = untrustedData.unwrap {
                    it as? MoveDataIn ?: throw Exception("MOVE Initiator flow cannot parse the received data")
                }
                inputs.addAll(moveData.utxoList)
                changeAmounts.addAll(moveData.changeAmounts)
            }

            // Stage 3.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Obtain a reference to the notary we want to use.
            require (inputs.map { it.state.notary }.distinct().size == 1) { "Notary must be identical across all inputs" }
            val notary = inputs[0].state.notary
            // Generate an unsigned transaction.
            // All owners must sign the move transaction
            val requiredParties = inputs.map { it.state.data.owner }.distinct().plus(newOwner)
            val txCommand = Command(MyCashContract.Commands.Move(), requiredParties.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addCommand(txCommand)

            // Add inputs
            for (input in inputs) {
                txBuilder.addInputState(input)
            }

            // Add outputs
            for (moveAmount in consolidatedGroupedByOwner.flatMap { it.value }) {
                txBuilder.addOutputState(moveAmount.copy(owner = newOwner), MyCash_Contract_ID)
            }

            // Add change amounts
            for (changeAmount in changeAmounts) {
                txBuilder.addOutputState(changeAmount, MyCash_Contract_ID)
            }

            // Stage 4.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 5.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 6.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val fullySignedTx = subFlow(MoveSigsInitiator(partSignedTx, requiredParties.minus(ourIdentity), GATHERING_SIGS.childProgressTracker()))

            // Stage 7.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterFlow: FlowSession) : FlowLogic<Unit>() {

        companion object {
            object UNWRAP_DATA : Step("Unwrapping data sent by MOVE Initiator flow.")
            object QUERY_VAULT : Step("Querying vault for unconsumed transaction outputs.")
            object CREATE_MOVE_INPUTS : Step("Creating move inputs.")
            object CREATE_CHANGE_INPUTS : Step("Creating change inputs.")
            object SEND_DATA : Step("Send data to MOVE Initiator flow.")

            fun tracker() = ProgressTracker(
                    UNWRAP_DATA,
                    QUERY_VAULT,
                    CREATE_MOVE_INPUTS,
                    CREATE_CHANGE_INPUTS,
                    SEND_DATA
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call() {
            // Step 1
            progressTracker.currentStep = UNWRAP_DATA
            val untrustedData = counterFlow.receive<MoveDataOut>()
            val moveData = untrustedData.unwrap {
                it as? MoveDataOut ?: throw Exception("MOVE Acceptor flow cannot parse the received data")
            }

            val utxoList = mutableListOf<StateAndRef<MyCash>>()
            val changeAmounts = mutableListOf<MyCash>()
            for (moveAmount in moveData.moveAmounts) {
                // The amount that we want to move
                val issuer = moveAmount.issuer
                val owner = moveAmount.owner
                val amount = moveAmount.amount.quantity
                val currencyCode = moveAmount.amount.token.product.currencyCode

                // Step 2
                progressTracker.currentStep = QUERY_VAULT
                val myCashCriteria = QueryCriteria.FungibleAssetQueryCriteria(issuer = listOf(issuer),
                        owner = listOf(owner),
                        status = Vault.StateStatus.UNCONSUMED
                )

                val unconsumedStates = builder {
                    val currencyIndex = MyCashSchemaV1.PersistentMyCash::currencyCode.equal(currencyCode)
                    val currencyCriteria = QueryCriteria.VaultCustomQueryCriteria(currencyIndex)
                    val criteria = myCashCriteria.and(currencyCriteria)
                    serviceHub.vaultService.queryBy<MyCash>(criteria).states
                }

                // Step 3
                progressTracker.currentStep = CREATE_MOVE_INPUTS
                var utxoSum = 0L
                for (utxo in unconsumedStates){
                    utxoSum += utxo.state.data.amount.quantity
                    utxoList.add(utxo)
                    // Gather enough cash
                    if (utxoSum > amount) {
                        break
                    }
                }

                require(utxoSum >= amount) { "$owner doesn't have enough cash! Only $utxoSum was found for move amount: {Issuer: $issuer, Owner: $owner, Amount: $amount, Currency Code: $currencyCode}" }

                // Step 4
                progressTracker.currentStep = CREATE_CHANGE_INPUTS
                val change = utxoSum - amount
                if (change > 0) {
                    val changeAmount = MyCash(owner, Amount(change, Issued(issuer.ref(OpaqueBytes.of(0x01)), Currency.getInstance(currencyCode))))
                    changeAmounts.add(changeAmount)
                }
            }

            // Step 5
            progressTracker.currentStep = SEND_DATA
            counterFlow.send(MoveDataIn(utxoList, changeAmounts))
        }
    }

    @InitiatingFlow
    class MoveSigsInitiator(val partSignedTx: SignedTransaction, val requiredParties: List<Party>, override val progressTracker: ProgressTracker): FlowLogic<SignedTransaction>() {

        companion object {
            object GATHERING_SIGS : Step("Gathering the counterparties' signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            fun tracker() = ProgressTracker(GATHERING_SIGS)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = GATHERING_SIGS
            val counterParties = requiredParties.map { initiateFlow(it) }
            val signedTx = subFlow(CollectSignaturesFlow(partSignedTx, counterParties, GATHERING_SIGS.childProgressTracker()))
            return signedTx
        }
    }

    @InitiatedBy(MoveSigsInitiator::class)
    class MoveSigsAcceptor(val counterFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val myCashOutputs = stx.tx.outputs.filter {
                        val myCash = it.data as? MyCash
                        if (myCash != null)
                            myCash.owner == ourIdentity
                        else
                            false
                    }
                    "This must be a MyCash transaction." using (myCashOutputs.isNotEmpty())
                }
            }
            return subFlow(signTransactionFlow)
        }
    }
}
