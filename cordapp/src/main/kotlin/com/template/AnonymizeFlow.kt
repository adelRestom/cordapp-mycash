package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.state.MyCash
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object AnonymizeFlow {
    @StartableByRPC
    class EncryptParties(val knownParties: List<AbstractParty>, override val progressTracker: ProgressTracker)
        : FlowLogic<List<AnonymousParty>>(){

        companion object {
            object ANONYMIZE : Step("Anonymize known parties.") {
                override fun childProgressTracker() = SwapIdentitiesFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    ANONYMIZE
            )
        }

        @Suspendable
        override fun call(): List<AnonymousParty> {
            // Anonymize known parties
            progressTracker.currentStep = ANONYMIZE
            val anonymousParties = mutableListOf<AnonymousParty>()
            knownParties.forEach { knownParty ->
                val anonymousParty = generateConfidentialIdentity(knownParty)
                anonymousParties.addAll(listOf(anonymousParty.first, anonymousParty.second).map { it })
            }

            return anonymousParties
        }

        @Suspendable
        private fun generateConfidentialIdentity(otherParty: AbstractParty): Pair<AnonymousParty, AnonymousParty> {
            val confidentialIdentities = subFlow(SwapIdentitiesFlow(
                    otherParty as Party,
                    false,
                    ANONYMIZE.childProgressTracker()))
            val anonymousMe = confidentialIdentities[ourIdentity]
                    ?: throw IllegalArgumentException("Could not anonymise my identity.")
            val anonymousOtherParty = confidentialIdentities[otherParty]
                    ?: throw IllegalArgumentException("Could not anonymise other party's identity.")

            return anonymousMe to anonymousOtherParty
        }
    }

    @StartableByRPC
    class EncryptStates(val knownCashList: List<MyCash>, override val progressTracker: ProgressTracker)
        : FlowLogic<Pair<List<MyCash>, List<AnonymousParty>>>(){

        companion object {
            object GO_INCOGNITO : Step("Update MyCash list with anonymous Issuers and Owners.") {
                override fun childProgressTracker() = SwapIdentitiesFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GO_INCOGNITO
            )
        }

        @Suspendable
        override fun call(): Pair<List<MyCash>, List<AnonymousParty>> {
            // Replace known issuers and owners with anonymous parties
            progressTracker.currentStep = GO_INCOGNITO
            val anonymousCashList = mutableListOf<MyCash>()
            val anonymousMe = mutableListOf<AnonymousParty>()

            knownCashList.forEach { knownCash ->
                val anonymousForIssuer = generateConfidentialIdentity(knownCash.issuer)
                anonymousMe.add(anonymousForIssuer.first)
                val anonymousForOwner = generateConfidentialIdentity(knownCash.owner)
                anonymousMe.add(anonymousForOwner.first)
                val anonymousCash = MyCash(anonymousForIssuer.second, anonymousForOwner.second, knownCash.amount.quantity, knownCash.amount.token.product.currencyCode)
                anonymousCashList.add(anonymousCash)
            }

            return anonymousCashList to anonymousMe
        }

        @Suspendable
        private fun generateConfidentialIdentity(otherParty: AbstractParty): Pair<AnonymousParty, AnonymousParty> {
            val confidentialIdentities = subFlow(SwapIdentitiesFlow(
                    otherParty as Party,
                    false,
                    GO_INCOGNITO.childProgressTracker()))
            val anonymousMe = confidentialIdentities[ourIdentity]
                    ?: throw IllegalArgumentException("Could not anonymise my identity.")
            val anonymousOtherParty = confidentialIdentities[otherParty]
                    ?: throw IllegalArgumentException("Could not anonymise other party's identity.")

            return anonymousMe to anonymousOtherParty
        }
    }

    @StartableByRPC
    class DecryptStates(val anonymousCashList: List<StateAndRef<MyCash>>, override val progressTracker: ProgressTracker)
        : FlowLogic<List<MyCash>>(){

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GO_WELL_KNOWN : Step("Update MyCash list with known Issuers and Owners.") {
                override fun childProgressTracker() = SwapIdentitiesFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GO_WELL_KNOWN
            )
        }

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): List<MyCash> {
            // Replace anonymous issuers and owners with known parties
            progressTracker.currentStep = GO_WELL_KNOWN
            val knownCashList = mutableListOf<MyCash>()
            anonymousCashList.map { it.state.data }.forEach { anonymousCash ->
                val knownIssuer = serviceHub.identityService.requireWellKnownPartyFromAnonymous(anonymousCash.issuer)
                val knownOwner = serviceHub.identityService.requireWellKnownPartyFromAnonymous(anonymousCash.owner)
                val knownCash = MyCash(knownIssuer, knownOwner, anonymousCash.amount.quantity, anonymousCash.amount.token.product.currencyCode)
                knownCashList.add(knownCash)
            }
            return knownCashList
        }
    }
}
