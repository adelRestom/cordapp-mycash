package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contract.MyCashContract
import com.template.state.MyCash
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object SignFinalize {

    @InitiatingFlow
    class Initiator(val txBuilder: TransactionBuilder, override val progressTracker: ProgressTracker) : FlowLogic<SignedTransaction>() {

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

            // Stage 2.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 3.
            progressTracker.currentStep = GATHERING_SIGS
            val requiredParties = txBuilder.commands().first().signers.map {
                serviceHub.identityService.requireWellKnownPartyFromAnonymous(AnonymousParty(it))
            }
            val counterParties = requiredParties.minus(ourIdentity).map { initiateFlow(it) }
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, counterParties, GATHERING_SIGS.childProgressTracker()))

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
                    stx.tx.commands.forEach {
                        when (it.value) {
                            is MyCashContract.Commands.Issue -> {
                                val myCashOutputs = stx.tx.outputs.filter {
                                    it.data is MyCash
                                            && ((it.data as MyCash).issuer == ourIdentity || (it.data as MyCash).owner == ourIdentity)
                                }
                                "This must be a MyCash transaction." using (myCashOutputs.isNotEmpty())
                            }

                            is MyCashContract.Commands.Move -> {
                                val myCashOutputs = stx.tx.outputs.filter {
                                    it.data is MyCash && (it.data as MyCash).owner == ourIdentity
                                }
                                "This must be a MyCash transaction." using (myCashOutputs.isNotEmpty())
                            }

                            is MyCashContract.Commands.Exit -> {
                                val inputs = serviceHub.loadStates(stx.tx.inputs.toSet()).map { it.state.data }
                                val myCashInputs = inputs.filter {
                                    it is MyCash && it.exitKeys.contains(ourIdentity.owningKey)
                                }
                                "This must be a MyCash transaction." using (myCashInputs.isNotEmpty())
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