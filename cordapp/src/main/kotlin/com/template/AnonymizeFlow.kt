package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.state.MyCash
import com.template.state.MyCashData
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object AnonymizeFlow {

    // Extension function to FlowLogic
    @Suspendable
    fun FlowLogic<*>.generateConfidentialIdentity(otherParty: Party, progressTracker: ProgressTracker): Pair<AnonymousParty, AnonymousParty> {
        val confidentialIdentities = subFlow(SwapIdentitiesFlow(
                otherParty as Party,
                false,
                progressTracker))
        val anonymousMe = confidentialIdentities[ourIdentity]
                ?: throw IllegalArgumentException("Could not anonymize my identity.")
        val anonymousOtherParty = confidentialIdentities[otherParty]
                ?: throw IllegalArgumentException("Could not anonymise other party's identity.")

        return anonymousMe to anonymousOtherParty
    }

    class EncryptParties(val anonymousIdentities: Map<Party, AnonymousParty>,
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
            val mutableAnonymousIdentities = anonymousIdentities.toMutableMap()
            knownParties.forEach { knownParty ->
                // Reusing anonymous identities
                if (!mutableAnonymousIdentities.containsKey(knownParty)) {
                    val anonymousPair = generateConfidentialIdentity(knownParty as Party,
                            ANONYMIZE.childProgressTracker())
                    mutableAnonymousIdentities.putIfAbsent(ourIdentity, anonymousPair.first)
                    mutableAnonymousIdentities[knownParty] = anonymousPair.second
                    anonymousParties.addAll(listOf(anonymousPair.first, anonymousPair.second).map { it })
                }
                else
                    anonymousParties.add(mutableAnonymousIdentities[knownParty] as AnonymousParty)
            }

            return anonymousParties
        }
    }

    class EncryptStates(val anonymousIdentities: Map<Party, AnonymousParty>, val knownCashList: List<MyCash>,
                        override val progressTracker: ProgressTracker)
        : FlowLogic<Triple<List<MyCashData>, Map<Party, AnonymousParty>, List<AnonymousParty>>>(){

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
        override fun call(): Triple<List<MyCashData>, Map<Party, AnonymousParty>, List<AnonymousParty>> {
            // Replace known issuers and owners with anonymous parties
            progressTracker.currentStep = GO_INCOGNITO
            val anonymousCashDataList = mutableListOf<MyCashData>()
            val anonymousMe = mutableListOf<AnonymousParty>()
            val mutableAnonymousIdentities = anonymousIdentities.toMutableMap()

            knownCashList.forEach { knownCash ->
                if (!mutableAnonymousIdentities.containsKey(knownCash.issuer)) {
                    val meAndIssuer = generateConfidentialIdentity(knownCash.issuer as Party,
                            GO_INCOGNITO.childProgressTracker())
                    anonymousMe.add(meAndIssuer.first)
                    mutableAnonymousIdentities.putIfAbsent(ourIdentity, meAndIssuer.first)
                    mutableAnonymousIdentities[knownCash.issuer as Party] = meAndIssuer.second
                }
                if (!mutableAnonymousIdentities.containsKey(knownCash.owner)) {
                    val meAndOwner = generateConfidentialIdentity(knownCash.owner as Party,
                            GO_INCOGNITO.childProgressTracker())
                    anonymousMe.add(meAndOwner.first)
                    mutableAnonymousIdentities.putIfAbsent(ourIdentity, meAndOwner.first)
                    mutableAnonymousIdentities[knownCash.owner as Party] = meAndOwner.second
                }
                // Reusing anonymous identities
                val anonymousCashData = MyCashData(mutableAnonymousIdentities[knownCash.issuer]!!, mutableAnonymousIdentities[knownCash.owner]!!,
                        knownCash.amount.quantity, knownCash.amount.token.product.currencyCode)
                anonymousCashDataList.add(anonymousCashData)
                // MyCash owner should be able to resolve all anonymous parties that are part of its state;
                // the below flow will register the issuer's anonymous identity certificate
                // in the owner's identity service
                progressTracker.currentStep = REGISTER_ANONYMOUS_ISSUER
                subFlow(RegisterIdentityFlow.Send(mutableAnonymousIdentities[knownCash.issuer] as AnonymousParty, knownCash.owner as Party,
                        REGISTER_ANONYMOUS_ISSUER.childProgressTracker()))
            }

            return Triple(anonymousCashDataList, mutableAnonymousIdentities, anonymousMe)
        }
    }

    class DecryptStates(val anonymousCashList: List<StateAndRef<MyCash>>, override val progressTracker: ProgressTracker)
        : FlowLogic<List<MyCashData>>(){

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
        override fun call(): List<MyCashData> {
            // Replace anonymous issuers and owners with known parties
            progressTracker.currentStep = GO_WELL_KNOWN
            val knownCashDataList = mutableListOf<MyCashData>()
            anonymousCashList.map { it.state.data }.forEach { anonymousCash ->
                val knownIssuer = serviceHub.identityService.requireWellKnownPartyFromAnonymous(anonymousCash.issuer)
                val knownOwner = serviceHub.identityService.requireWellKnownPartyFromAnonymous(anonymousCash.owner)
                val knownCashData = MyCashData(knownIssuer, knownOwner, anonymousCash.amount.quantity, anonymousCash.amount.token.product.currencyCode)
                knownCashDataList.add(knownCashData)
            }
            return knownCashDataList
        }
    }
}
