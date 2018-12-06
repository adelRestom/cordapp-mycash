package com.template.contract

import com.template.MyCash
import com.template.MyCashContract
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Issued
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.OpaqueBytes
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test
import java.util.*
import com.template.MyCashContract.Companion.MyCash_Contract_ID

class MyCashContractTests {
    private val ledgerServices = MockServices(listOf("com.template"))
    private val bankCorp = TestIdentity(CordaX500Name("Bank", "New York", "US"))
    private val aCorp = TestIdentity(CordaX500Name("PartyA", "London", "GB"))
    private val bCorp = TestIdentity(CordaX500Name("PartyB", "New York", "US"))
    private val USD25 = Amount(2500, Issued(bankCorp.party.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))
    private val USD50 = Amount(5000, Issued(bankCorp.party.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))
    private val USD75 = Amount(7500, Issued(bankCorp.party.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))
    private val USD100 = Amount(10000, Issued(bankCorp.party.ref(OpaqueBytes.of(0x01)), Currency.getInstance("USD")))

    @Test
    fun `ISSUE transaction - must have no inputs`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                command(listOf(bankCorp.publicKey, aCorp.publicKey), MyCashContract.Commands.Issue())
                `fails with`("No inputs should be consumed when issuing new cash.")
            }
        }
    }

    @Test
    fun `ISSUE transaction - must have at least one output`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                output(MyCash_Contract_ID, MyCash(bankCorp.party, bCorp.party, USD100))
                command(listOf(bankCorp.publicKey, aCorp.publicKey), MyCashContract.Commands.Issue())
                verifies()
            }
        }
    }

    @Test
    fun `ISSUE transaction - issuer cannot be owner`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bankCorp.party, bankCorp.party, USD100))
                command(listOf(bankCorp.publicKey, aCorp.publicKey), MyCashContract.Commands.Issue())
                `fails with`("The issuer and the owner cannot be the same entity.")
            }
        }
    }

    @Test
    fun `ISSUE transaction - issuer must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Issue())
                `fails with`("Issuer must sign the transaction.")
            }
        }
    }

    @Test
    fun `MOVE transaction - must have at least one input and one output`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                output(MyCash_Contract_ID, MyCash(bankCorp.party, bCorp.party, USD100))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Move())
                verifies()
            }
        }
    }

    @Test
    fun `MOVE transaction - sum(inputs) = sum(outputs)`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                input(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD25))
                output(MyCash_Contract_ID, MyCash(bankCorp.party, bCorp.party, USD50))
                output(MyCash_Contract_ID, MyCash(bankCorp.party, bCorp.party, USD75))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Move())
                verifies()
            }
        }
    }

    @Test
    fun `EXIT transaction - must have no outputs`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                output(MyCash_Contract_ID, MyCash(bankCorp.party, bCorp.party, USD100))
                command(listOf(bankCorp.publicKey, aCorp.publicKey), MyCashContract.Commands.Exit())
                `fails with`("There should be no outputs.")
            }
        }
    }

    @Test
    fun `EXIT transaction - issuer must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bankCorp.party, aCorp.party, USD100))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Exit())
                `fails with`("Issuer and Owners must sign the exit command.")
            }
        }
    }
}