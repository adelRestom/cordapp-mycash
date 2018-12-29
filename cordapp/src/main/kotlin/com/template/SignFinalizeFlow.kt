package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.state.MyCash
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object SignFinalize {

    @InitiatingFlow
    class Initiator(val txBuilder: TransactionBuilder, override val progressTracker: ProgressTracker,
                    val anonymous: Boolean = false): FlowLogic<SignedTransaction>() {

        companion object {
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparties' signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {
            // Stage 1.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            val signers = txBuilder.commands().flatMap { it.signers }
            val fullySignedTx: SignedTransaction
            if (anonymous) {
                // Stage 2.
                progressTracker.currentStep = SIGNING_TRANSACTION
                // Extract my anonymous identity public keys
                val anonymousMe = signers.filter {
                    serviceHub.identityService.requireWellKnownPartyFromAnonymous(AnonymousParty(it)) == ourIdentity
                }
                val partSignedTx = serviceHub.signInitialTransaction(txBuilder, anonymousMe)

                // Stage 3.
                progressTracker.currentStep = GATHERING_SIGS
                val anonymousOtherParties = signers.minus(anonymousMe).map {
                    serviceHub.identityService.requireWellKnownPartyFromAnonymous(AnonymousParty(it))
                }.map { initiateFlow(it) }
                fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, anonymousOtherParties,
                        anonymousMe, GATHERING_SIGS.childProgressTracker()))
            }
            else {
                // Stage 2.
                progressTracker.currentStep = SIGNING_TRANSACTION
                // Sign the transaction with my known identity
                val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

                // Stage 3.
                progressTracker.currentStep = GATHERING_SIGS
                val requiredParties = signers.map {
                    serviceHub.identityService.requireWellKnownPartyFromAnonymous(AnonymousParty(it))
                }
                val counterParties = requiredParties.minus(ourIdentity).map { initiateFlow(it) }
                fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, counterParties, GATHERING_SIGS.childProgressTracker()))
            }

            // Stage 4.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in all parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    stx.tx.commands.forEach { command ->
                        when (command.value) {
                            is MyCashContract.Commands.Issue -> {
                                val myCashOutputs = stx.tx.outputs.filter { it.data is MyCash }.map { it.data as MyCash }
                                "This must be a MyCash transaction." using (myCashOutputs.isNotEmpty())
                                val filteredList = myCashOutputs.filter {
                                    serviceHub.identityService.wellKnownPartyFromAnonymous(it.issuer) == ourIdentity
                                            || serviceHub.identityService.wellKnownPartyFromAnonymous(it.owner) == ourIdentity
                                }
                                "ISSUE transaction does not contain the requested signer's identity." using (filteredList.isNotEmpty())
                            }

                            is MyCashContract.Commands.Move -> {
                                val myCashOutputs = stx.tx.outputs.filter {
                                    it.data is MyCash && (it.data as MyCash).owner == ourIdentity
                                }
                                "This must be a MyCash transaction." using (myCashOutputs.isNotEmpty())
                            }

                            is MyCashContract.Commands.Exit -> {
                                val myCashInputs = serviceHub.loadStates(stx.tx.inputs.toSet()).map { it.state.data as MyCash }
                                "This must be a MyCash transaction." using (myCashInputs.isNotEmpty())
                                val filteredList = myCashInputs.filter {
                                    serviceHub.identityService.wellKnownPartyFromAnonymous(it.issuer) == ourIdentity
                                            || serviceHub.identityService.wellKnownPartyFromAnonymous(it.owner) == ourIdentity
                                }
                                "EXIT transaction does not contain the requested signer's identity." using (filteredList.isNotEmpty())
                            }

                            else -> throw FlowException("Unrecognised MyCash command")
                        }
                    }
                }
            }
            return subFlow(signTransactionFlow)
        }
    }
}