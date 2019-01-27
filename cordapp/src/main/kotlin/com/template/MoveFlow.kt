package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.contract.MyCashContract.Companion.MyCash_Contract_ID
import com.template.schema.MyCashSchemaV1
import com.template.state.MyCash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.Issued
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
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
    data class MoveDataIn (val utxoList: MutableList<StateAndRef<MyCash>>, val changeAmounts: MutableList<MyCash>,
                           val anonymousIdentities: MutableMap<AbstractParty, AbstractParty>)

    // Composite key that is used to group move amounts
    data class Key(val issuer: AbstractParty, val owner: AbstractParty, val currencyCode: String)

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val moveAmounts: List<MyCash>, val newOwner: AbstractParty
                    , val anonymous: Boolean = false) : FlowLogic<SignedTransaction>() {

        init {
            require(moveAmounts.isNotEmpty()) { "Move amounts list cannot be empty" }
            require(moveAmounts.filter { it.amount.quantity <= 0 }.isEmpty()) { "Move amount must be greater than zero" }
        }

        // Constructor to move one amount
        constructor(issuer: AbstractParty, owner: AbstractParty, amount: Long, currencyCode: String,
                    newOwner: Party, anonymous: Boolean = false):
                this(listOf(MyCash(issuer, owner, amount, currencyCode)), newOwner, anonymous)

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object CONSOLIDATE_INPUTS : Step("Consolidate inputs by owner/issuer/currency code.")
            object FETCHING_INPUTS : Step("Fetching owners' input states.") {
                override fun childProgressTracker() = Acceptor.tracker()
            }
            object GENERATING_TRANSACTION : Step("Generating transaction.")
            object GENERATE_CONFIDENTIAL_STATES : Step("Generating confidential states.") {
                override fun childProgressTracker() = AnonymizeFlow.EncryptStates.tracker()
            }
            object GENERATE_CONFIDENTIAL_IDS : Step("Generating confidential identities for the transaction.") {
                override fun childProgressTracker() = AnonymizeFlow.EncryptParties.tracker()
            }
            object SIGN_FINALIZE : Step("Signing transaction and finalizing state.") {
                override fun childProgressTracker() = SignFinalize.Initiator.tracker()
            }

            fun tracker() = ProgressTracker(
                    CONSOLIDATE_INPUTS,
                    FETCHING_INPUTS,
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
            progressTracker.currentStep = CONSOLIDATE_INPUTS
            // We will consolidate move amounts by owner/issuer/currency then group them by owner to minimize
            // the number of trips to owners' vaults
            val consolidatedGroupedByOwner = moveAmounts
                    .groupBy { Key(it.issuer, it.owner, it.amount.token.product.currencyCode) }
                    .map {
                        val sum = it.value.fold(0L) { sum, myCash ->
                            sum + myCash.amount.quantity
                        }
                        MyCash(it.key.issuer, it.key.owner, sum, it.key.currencyCode)
                    }
                    .toList().groupBy { it.owner }

            // Stage 2.
            progressTracker.currentStep = FETCHING_INPUTS
            // This list will hold all the UTXO that we can use to fulfill the move amounts
            val inputs = mutableListOf<StateAndRef<MyCash>>()
            // This list will hold new outputs to return change to owners
            val changeAmounts = mutableListOf<MyCash>()
            // This map will allows us to reuse existing anonymous identities
            val anonymousIdentities = mutableMapOf<AbstractParty, AbstractParty>()
            // Query owners' vaults to gather UTXO's
            consolidatedGroupedByOwner.forEach {
                // Our key is the owner
                val counterParty = initiateFlow(it.key as Party)
                val untrustedData = counterParty.sendAndReceive<MoveDataIn>(MoveDataOut(it.value))
                val moveData = untrustedData.unwrap {data ->
                    data as? MoveDataIn ?: throw FlowException("MOVE Initiator flow cannot parse the received data")
                }
                inputs.addAll(moveData.utxoList)
                changeAmounts.addAll(moveData.changeAmounts)
                moveData.anonymousIdentities.forEach{ identity -> anonymousIdentities.putIfAbsent(identity.key, identity.value) }
            }

            // Stage 3.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Obtain a reference to the notary we want to use
            require (inputs.map { it.state.notary }.distinct().size == 1) { "Notary must be identical across all inputs" }
            val notary = inputs[0].state.notary
            val txBuilder = TransactionBuilder(notary)
            val txCommand: Command<MyCashContract.Commands.Move>
            // Owners (old and new) must sign the transaction
            val requiredParties = inputs.map { it.state.data.owner }.distinct().plus(newOwner)

            if (anonymous) {
                progressTracker.currentStep = GENERATE_CONFIDENTIAL_STATES
                // Add outputs with anonymous issuers and owners
                val anonymousOutputs = subFlow(AnonymizeFlow.EncryptStates(
                        anonymousIdentities, consolidatedGroupedByOwner.flatMap { it.value }.map { it.copy(owner = newOwner) },
                        GENERATE_CONFIDENTIAL_STATES.childProgressTracker()))
                anonymousOutputs.first.forEach {
                    txBuilder.addOutputState(it, MyCash_Contract_ID)
                }
                // Add newly generated confidential identities if any
                anonymousOutputs.second.forEach {
                    anonymousIdentities.putIfAbsent(it.key, it.value)
                }

                // Add change amounts with anonymous issuers and owners
                val anonymousChangeAmounts = subFlow(AnonymizeFlow.EncryptStates(
                        anonymousIdentities, changeAmounts, GENERATE_CONFIDENTIAL_STATES.childProgressTracker()))
                anonymousChangeAmounts.first.forEach {
                    txBuilder.addOutputState(it, MyCash_Contract_ID)
                }
                // Add newly generated confidential identities if any
                anonymousChangeAmounts.second.forEach {
                    anonymousIdentities.putIfAbsent(it.key, it.value)
                }

                progressTracker.currentStep = GENERATE_CONFIDENTIAL_IDS
                // Inputs might have a mix of known and anonymous parties; we will only encrypt the known ones
                val encryptedParties = subFlow(AnonymizeFlow.EncryptParties(
                        anonymousIdentities, requiredParties.filter { it is Party },
                        GENERATE_CONFIDENTIAL_IDS.childProgressTracker()))
                // Anonymous parties are required to sign the transaction = old owners + new owner + me
                val anonymousParties = listOf(
                        requiredParties.filter { it is AnonymousParty }.map { it as AnonymousParty },
                        encryptedParties, anonymousOutputs.third.map { it }, anonymousChangeAmounts.third.map { it }).flatten()
                txCommand = Command(MyCashContract.Commands.Move(), anonymousParties.map { it.owningKey })
            }
            else {
                txCommand = Command(MyCashContract.Commands.Move(), requiredParties.map { it.owningKey })
                // Add well known outputs
                consolidatedGroupedByOwner.flatMap { it.value }.forEach {moveAmount ->
                    txBuilder.addOutputState(moveAmount.copy(owner = newOwner), MyCash_Contract_ID)
                }
                // Add well known change amounts
                changeAmounts.forEach {
                    txBuilder.addOutputState(it, MyCash_Contract_ID)
                }
            }
            txBuilder.addCommand(txCommand)
            // Add inputs
            inputs.forEach { txBuilder.addInputState(it) }

            // Stage 4.
            progressTracker.currentStep = SIGN_FINALIZE
            // Signing transaction and finalizing state
            return subFlow(SignFinalize.Initiator(txBuilder, progressTracker = SIGN_FINALIZE.childProgressTracker()))
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
                it as? MoveDataOut ?: throw FlowException("MOVE Acceptor flow cannot parse the received data")
            }

            val utxoList = mutableListOf<StateAndRef<MyCash>>()
            val changeAmounts = mutableListOf<MyCash>()
            val anonymousIdentities = mutableMapOf<AbstractParty, AbstractParty>()
            for (moveAmount in moveData.moveAmounts) {
                // The amount that we want to move
                val issuer = moveAmount.issuer
                val owner = moveAmount.owner
                val amount = moveAmount.amount.quantity
                val currencyCode = moveAmount.amount.token.product.currencyCode

                // Step 2
                progressTracker.currentStep = QUERY_VAULT
                val myCashCriteria = QueryCriteria.FungibleAssetQueryCriteria(
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
                    val knownIssuer = serviceHub.identityService.wellKnownPartyFromAnonymous(utxo.state.data.issuer)
                    val knownOwner = serviceHub.identityService.wellKnownPartyFromAnonymous(utxo.state.data.owner)
                    if (knownIssuer == issuer && knownOwner == owner) {
                        utxoSum += utxo.state.data.amount.quantity
                        utxoList.add(utxo)
                        // Gather enough cash
                        if (utxoSum >= amount) {
                            break
                        }
                        // Store anonymous identities so that we can reuse them
                        if (utxo.state.data.issuer is AnonymousParty)
                            anonymousIdentities.putIfAbsent(issuer, utxo.state.data.issuer)
                        if (utxo.state.data.owner is AnonymousParty)
                            anonymousIdentities.putIfAbsent(owner, utxo.state.data.owner)
                    }
                }

                if (utxoSum < amount) {
                    throw FlowException("$owner doesn't have enough cash! Only $utxoSum was found for move amount: {Issuer: $issuer, Owner: $owner, Amount: $amount, Currency Code: $currencyCode}")
                }

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
            counterFlow.send(MoveDataIn(utxoList, changeAmounts, anonymousIdentities))
        }
    }
}