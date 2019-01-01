package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

// Based on Corda's IdentitySyncFlow
object RegisterIdentityFlow {
    @InitiatingFlow
    class Send(val anonymousParty: AnonymousParty, val receiver: Party, override val progressTracker: ProgressTracker) : FlowLogic<Unit>() {

        companion object {
            object SENDING_CERTIFICATE : ProgressTracker.Step("Sending certificate.")

            fun tracker() = ProgressTracker(SENDING_CERTIFICATE)
        }

        @Suspendable
        override fun call() {
            progressTracker.currentStep = SENDING_CERTIFICATE
            val certificate = serviceHub.identityService.certificateFromKey(anonymousParty.owningKey)
            val counterParty = initiateFlow(receiver)
            counterParty.send(certificate!!)
        }
    }

    @InitiatedBy(Send::class)
    class Receive(val otherSideSession: FlowSession) : FlowLogic<Unit>() {
        companion object {
            object RECEIVING_CERTIFICATE : ProgressTracker.Step("Receiving certificate for unknown identity")
            object VERIFY_REGISTER : ProgressTracker.Step("Verifying and registering certificate.")
        }

        override val progressTracker: ProgressTracker = ProgressTracker(
                RECEIVING_CERTIFICATE,
                VERIFY_REGISTER
        )

        @Suspendable
        override fun call(): Unit {
            progressTracker.currentStep = RECEIVING_CERTIFICATE
            val untrustedData = otherSideSession.receive<PartyAndCertificate>()

            progressTracker.currentStep = VERIFY_REGISTER
            // Verify the certificate we've received before storing it
            val certificate = untrustedData.unwrap { certificate ->
                certificate.verify(serviceHub.identityService.trustAnchor)
                certificate
            }

            // Store the received confidential identity in the identity service,
            // so we have a record of which well known identity it maps to
            serviceHub.identityService.verifyAndRegisterIdentity(certificate)
        }
    }
}