package com.template.contract

import com.template.contract.MyCashContract.Companion.MyCash_Contract_ID
import com.template.state.MyCash
import net.corda.core.identity.CordaX500Name
import net.corda.finance.GBP
import net.corda.finance.USD
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test

class MyCashContractTests {
    private val ledgerServices = MockServices()
    private val bank1 = TestIdentity(CordaX500Name("Bank 1", "New York", "US"))
    private val bank2 = TestIdentity(CordaX500Name("Bank 2", "London", "GB"))
    private val aCorp = TestIdentity(CordaX500Name("PartyA", "London", "GB"))
    private val bCorp = TestIdentity(CordaX500Name("PartyB", "New York", "US"))
    private val cCorp = TestIdentity(CordaX500Name("PartyC", "Moscow", "RU"))
    private val currencyCode1 = USD.currencyCode
    private val currencyCode2 = GBP.currencyCode

    @Test
    fun `ISSUE transaction must have no inputs`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                command(listOf(bank1.publicKey, aCorp.publicKey), MyCashContract.Commands.Issue())
                `fails with`("No inputs should be consumed during MyCash ISSUE.")
            }
        }
    }

    @Test
    fun `ISSUE transaction must have at least one output`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                command(listOf(bank1.publicKey, aCorp.publicKey), MyCashContract.Commands.Issue())
                verifies()
            }
        }
    }

    @Test
    fun `ISSUE transaction issuers cannot be the same as owners`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bank1.party, bank1.party, 100L, currencyCode1))
                command(listOf(bank1.publicKey, aCorp.publicKey), MyCashContract.Commands.Issue())
                `fails with`("Issuers and owners cannot be the same entity.")
            }
        }
    }

    @Test
    fun `ISSUE transaction issuers must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank2.party, bCorp.party, 25L, currencyCode2))
                command(listOf(aCorp.publicKey, bCorp.publicKey), MyCashContract.Commands.Issue())
                `fails with`("Issuers must sign MyCash ISSUE transaction.")
            }
        }
    }

    @Test
    fun `ISSUE transaction owners must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                output(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank2.party, bCorp.party, 25L, currencyCode2))
                command(listOf(bank1.publicKey, bank2.publicKey), MyCashContract.Commands.Issue())
                `fails with`("Owners must sign MyCash ISSUE transaction.")
            }
        }
    }

    @Test
    fun `MOVE transaction must have at least one input and one output`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank1.party, bCorp.party, 100L, currencyCode1))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Move())
                verifies()
            }
        }
    }

    @Test
    fun `MOVE transaction sum(inputs by issuer & currency code) = sum(outputs by issuer & currency code)`() {
        ledgerServices.ledger {
            transaction {
                // Inputs
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 75L, currencyCode1))
                input(MyCash_Contract_ID, MyCash(bank2.party, aCorp.party, 25L, currencyCode1))
                input(MyCash_Contract_ID, MyCash(bank1.party, bCorp.party, 25L, currencyCode2))
                // Outputs
                output(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 50L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank1.party, cCorp.party, 25L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank2.party, cCorp.party, 25L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank1.party, bCorp.party, 20L, currencyCode2))
                output(MyCash_Contract_ID, MyCash(bank1.party, cCorp.party, 5L, currencyCode2))
                command(listOf(aCorp.publicKey, bCorp.publicKey), MyCashContract.Commands.Move())
                verifies()
            }
        }
    }

    @Test
    fun `MOVE transaction previous owners must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 75L, currencyCode1))
                input(MyCash_Contract_ID, MyCash(bank1.party, bCorp.party, 25L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank1.party, cCorp.party, 100L, currencyCode1))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Move())
                `fails with`("Previous owners must sign MyCash MOVE transaction.")
            }
        }
    }

    @Test
    fun `EXIT transaction must have no outputs`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                output(MyCash_Contract_ID, MyCash(bank1.party, bCorp.party, 100L, currencyCode1))
                command(listOf(aCorp.publicKey), MyCashContract.Commands.Exit())
                `fails with`("There shouldn't be any outputs created.")
            }
        }
    }

    @Test
    fun `EXIT transaction issuers must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                input(MyCash_Contract_ID, MyCash(bank2.party, bCorp.party, 100L, currencyCode2))
                command(listOf(bank1.publicKey, aCorp.publicKey, bCorp.publicKey), MyCashContract.Commands.Exit())
                `fails with`("Exit key owners must sign MyCash EXIT transaction.")
            }
        }
    }

    @Test
    fun `EXIT transaction owners must sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                input(MyCash_Contract_ID, MyCash(bank1.party, aCorp.party, 100L, currencyCode1))
                input(MyCash_Contract_ID, MyCash(bank2.party, bCorp.party, 100L, currencyCode2))
                command(listOf(bank1.publicKey, bank2.publicKey, aCorp.publicKey), MyCashContract.Commands.Exit())
                `fails with`("Exit key owners must sign MyCash EXIT transaction.")
            }
        }
    }
}