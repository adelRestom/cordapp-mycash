package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.state.MyCash
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object AnonymizeFlow {
    class EncryptParties(val anonymousIdentities: MutableMap<AbstractParty, AbstractParty>,
                         val knownParties: List<AbstractParty>, override val progressTracker: ProgressTracker)
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
                // Reusing anonymous identities
                if (!anonymousIdentities.containsKey(knownParty)) {
                    val anonymousPair = generateConfidentialIdentity(knownParty)
                    anonymousIdentities.putIfAbsent(ourIdentity, anonymousPair.first)
                    anonymousIdentities[knownParty] = anonymousPair.second
                    anonymousParties.addAll(listOf(anonymousPair.first, anonymousPair.second).map { it })
                }
                else
                    anonymousParties.add(anonymousIdentities[knownParty] as AnonymousParty)
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

    class EncryptStates(val anonymousIdentities: MutableMap<AbstractParty, AbstractParty>, val knownCashList: List<MyCash>,
                        override val progressTracker: ProgressTracker)
        : FlowLogic<Triple<List<MyCash>, MutableMap<AbstractParty, AbstractParty>, List<AnonymousParty>>>(){

        companion object {
            object GO_INCOGNITO : Step("Update MyCash list with anonymous Issuers and Owners.") {
                override fun childProgressTracker() = SwapIdentitiesFlow.tracker()
            }

            object REGISTER_ANONYMOUS_ISSUER : Step("Passing anonymous issuer certificate to owner.") {
                override fun childProgressTracker() = RegisterIdentityFlow.Send.tracker()
            }

            fun tracker() = ProgressTracker(
                    GO_INCOGNITO,
                    REGISTER_ANONYMOUS_ISSUER
            )
        }

        @Suspendable
        override fun call(): Triple<List<MyCash>, MutableMap<AbstractParty, AbstractParty>, List<AnonymousParty>> {
            // Replace known issuers and owners with anonymous parties
            progressTracker.currentStep = GO_INCOGNITO
            val anonymousCashList = mutableListOf<MyCash>()
            val anonymousMe = mutableListOf<AnonymousParty>()

            knownCashList.forEach { knownCash ->
                if (!anonymousIdentities.containsKey(knownCash.issuer)) {
                    val meAndIssuer = generateConfidentialIdentity(knownCash.issuer)
                    anonymousMe.add(meAndIssuer.first)
                    anonymousIdentities.putIfAbsent(ourIdentity, meAndIssuer.first)
                    anonymousIdentities[knownCash.issuer] = meAndIssuer.second
                }
                if (!anonymousIdentities.containsKey(knownCash.owner)) {
                    val meAndOwner = generateConfidentialIdentity(knownCash.owner)
                    anonymousMe.add(meAndOwner.first)
                    anonymousIdentities.putIfAbsent(ourIdentity, meAndOwner.first)
                    anonymousIdentities[knownCash.owner] = meAndOwner.second
                }
                // Reusing anonymous identities
                val anonymousCash = MyCash(anonymousIdentities[knownCash.issuer]!!, anonymousIdentities[knownCash.owner]!!,
                        knownCash.amount.quantity, knownCash.amount.token.product.currencyCode)
                anonymousCashList.add(anonymousCash)
                // MyCash owner should be able to resolve all anonymous parties that are part of its state;
                // the below flow will register the issuer's anonymous identity certificate
                // in the owner's identity service
                progressTracker.currentStep = REGISTER_ANONYMOUS_ISSUER
                subFlow(RegisterIdentityFlow.Send(anonymousIdentities[knownCash.issuer] as AnonymousParty, knownCash.owner as Party,
                        REGISTER_ANONYMOUS_ISSUER.childProgressTracker()))
            }

            return Triple(anonymousCashList, anonymousIdentities, anonymousMe)
        }

        @Suspendable
        private fun generateConfidentialIdentity(otherParty: AbstractParty): Pair<AnonymousParty, AnonymousParty> {
            val confidentialIdentities = subFlow(SwapIdentitiesFlow(
                    otherParty as Party,
                    false,
                    GO_INCOGNITO.childProgressTracker()))
            val anonymousMe = confidentialIdentities[ourIdentity]
                    ?: throw IllegalArgumentException("Could not anonymize my identity.")
            val anonymousOtherParty = confidentialIdentities[otherParty]
                    ?: throw IllegalArgumentException("Could not anonymise other party's identity.")

            return anonymousMe to anonymousOtherParty
        }
    }

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
