package com.template.flow

import com.template.ExitFlow
import com.template.IssueFlow
import com.template.MoveFlow
import com.template.state.MyCash
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.finance.GBP
import net.corda.finance.USD
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class MyCashFlowTests {
    lateinit var network: MockNetwork
    lateinit var bank1: StartedMockNode
    lateinit var bank2: StartedMockNode
    lateinit var aCorp: StartedMockNode
    lateinit var bCorp: StartedMockNode
    lateinit var cCorp: StartedMockNode
    val amount1 = 100L
    val amount2 = 50L
    val currencyCode1 = USD.currencyCode
    val currencyCode2 = GBP.currencyCode
    lateinit var myCash1: MyCash
    lateinit var myCash2: MyCash

    @Before
    fun setup() {
        network = MockNetwork(listOf("com.template.contract", "com.template.schema"))
        bank1 = network.createPartyNode(legalName = CordaX500Name(organisation = "Bank1", locality = "New York", country = "US"))
        bank2 = network.createPartyNode(legalName = CordaX500Name(organisation = "Bank2", locality = "London", country = "GB"))
        aCorp = network.createPartyNode(legalName = CordaX500Name(organisation = "Corp A", locality = "New York", country = "US"))
        bCorp = network.createPartyNode(legalName = CordaX500Name(organisation = "Corp B", locality = "London", country = "GB"))
        cCorp = network.createPartyNode(legalName = CordaX500Name(organisation = "Corp C", locality = "Moscow", country = "RU"))
        myCash1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), amount1, currencyCode1)
        myCash2 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), amount2, currencyCode2)
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(IssueFlow.Acceptor::class.java) }
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(MoveFlow.Acceptor::class.java) }
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(ExitFlow.Acceptor::class.java) }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    /*@Test
    fun `ISSUE transaction is signed by the issuers and the owners`() {
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        signedTx.verifyRequiredSignatures()
    }

    @Test
    fun `ISSUE flow records a transaction in all participants' transaction storage`() {
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        for (node in listOf(bank1, aCorp, bank2, bCorp)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `Issue flow recorded transaction has no inputs and one or more MyCash outputs`() {
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in all participants' transaction storage
        for (node in listOf(bank1, aCorp, bank2, bCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            if (recordedTx != null) {
                assert(recordedTx.tx.inputs.isEmpty())
                val txOutputs = recordedTx.tx.outputs
                val myCashOutputs = txOutputs.filter {
                    val myCash = it.data as? MyCash
                    if (myCash != null)
                        myCash.issuer == node.info.singleIdentity() || myCash.owner == node.info.singleIdentity()
                    else
                        false
                }
                assert(myCashOutputs.isNotEmpty())
                if (node == aCorp || node == bank1)
                    assert(myCashOutputs.map { it.data }.contains(myCash1))
                else
                    assert(myCashOutputs.map { it.data }.contains(myCash2))
            }
            else{
                throw Exception("Transaction is not recorded in $node")
            }
        }
    }*/

    @Test
    fun `ISSUE flow records the correct MyCash state in the participants' vaults`() {
        val flow = IssueFlow.Initiator(listOf(myCash1))
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // Transaction is recorded in the participants' transaction storage
        for (node in listOf(bank1, aCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            if (recordedTx != null) {
                assert(recordedTx.tx.inputs.isEmpty())
                val txOutputs = recordedTx.tx.outputs
                val myCashOutputs = txOutputs.filter {
                    val myCash = it.data as? MyCash
                    if (myCash != null)
                        myCash.issuer == node.info.singleIdentity() || myCash.owner == node.info.singleIdentity()
                    else
                        false
                }
                assert(myCashOutputs.isNotEmpty())
                if (node == aCorp || node == bank1)
                    assert(myCashOutputs.map { it.data }.contains(myCash1))
                else
                    assert(myCashOutputs.map { it.data }.contains(myCash2))
            }
            else{
                throw Exception("Transaction is not recorded in $node")
            }
        }

        // Owner
        aCorp.transaction {
            val myCashStates = aCorp.services.vaultService.queryBy<MyCash>().states
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("aCorp: "+myCashStates.size)
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
        }

        // Issuer
        bank1.transaction {
            val myCashStates = bank1.services.vaultService.queryBy<MyCash>().states
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
            println("bank1: "+myCashStates.size)
            println("\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n")
        }
    }

    /*@Test
    fun `Recorded EXIT MyCash transaction has no outputs and one or more MyCash inputs`() {
        // Create MyCash
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        bank1.startFlow(flow)
        network.runNetwork()

        val aCorpCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }
        val bCorpCash = bCorp.transaction {
            bCorp.services.vaultService.queryBy<MyCash>().states
        }
        val refs = listOf(aCorpCash.single().ref, bCorpCash.single().ref)

        // EXIT MyCash
        val exitFlow = ExitFlow.Initiator(refs)
        val exitFuture = bank2.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()

        // We check the recorded transaction in all participants' transaction storage
        for (node in listOf(bank1, aCorp, bank2, bCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(exitSignedTx.id)
            if (recordedTx != null) {
                val txOutputs = recordedTx.tx.outputs
                assert(txOutputs.isEmpty())

                val txInputs = recordedTx!!.tx.inputs.map { node.services.toStateAndRef<MyCash>(it) }
                assert(txInputs.isNotEmpty())
                if (node == aCorp || node == bank1)
                    assert(txInputs.map { it.state.data }.contains(myCash1))
                else
                    assert(txInputs.map { it.state.data }.contains(myCash2))
            }
            else{
                throw Exception("Transaction is not recorded in $node")
            }
        }
    }
    
    @Test
    fun `EXIT flow consumes MyCash in all participants' vaults`() {
        // Create MyCash
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        bank1.startFlow(flow)
        network.runNetwork()

        val aCorpCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }
        val bCorpCash = bCorp.transaction {
            bCorp.services.vaultService.queryBy<MyCash>().states
        }
        val refs = listOf(aCorpCash.single().ref, bCorpCash.single().ref)

        // EXIT MyCash
        val exitFlow = ExitFlow.Initiator(refs)
        val exitFuture = bank2.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()
    
        // MyCash is now marked as consumed
        bank1.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bank1.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        bank2.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bank2.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        bCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }
    }*/

    /*@Test
    fun `Recorded MOVE MyCash transaction has one or more MyCash inputs and one or more MyCash outputs`() {
        // Create MyCash
        // Issuing 100USD and 50GBP to aCorp
        val flow = IssueFlow.Initiator(listOf(myCash2))
        bank1.startFlow(flow)
        network.runNetwork()

        bank2.transaction {
            val myCashStates = bank2.services.vaultService.queryBy<MyCash>().states
            assertEquals(myCashStates.size, 1)
            val recordedState = myCashStates.single().state.data
            assertEquals(recordedState.issuer, bank2.info.singleIdentity())
            assertEquals(recordedState.owner, bCorp.info.singleIdentity())
            assertEquals(recordedState.amount.quantity, amount2)
            assertEquals(recordedState.amount.token.product.currencyCode, currencyCode2)
        }

        val moveAmount1 = 35L
        val moveAmount2 = 14L
//        val moveCash1 = myCash1.copy(amount = myCash1.amount.copy(quantity = moveAmount1))
        val moveCash2 = myCash2.copy(amount = myCash2.amount.copy(quantity = moveAmount2))

        // EXIT MyCash
        val moveFlow = MoveFlow.Initiator(listOf(moveCash2), cCorp.info.singleIdentity())
        val moveFuture = aCorp.startFlow(moveFlow)
        network.runNetwork()
        val moveSignedTx = moveFuture.getOrThrow()

        // We check the recorded transaction in all participants' transaction storage
        for (node in listOf(cCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(moveSignedTx.id)
            if (recordedTx != null) {
                val txInputs = recordedTx.tx.inputs.map { node.services.toStateAndRef<MyCash>(it) }
                assert(txInputs.isNotEmpty())

                val txOutputs = recordedTx.tx.outputs.filter { it.data is MyCash }
                assert(txOutputs.isNotEmpty())
                assert(txOutputs.map { it.data }.containsAll(listOf(moveCash2.copy(owner = cCorp.info.singleIdentity()))))
            }
            else{
                throw Exception("Transaction is not recorded in $node")
            }
        }
    }*/

    /*@Test
    fun `EXIT flow consumes MyCash in all participants' vaults`() {
        // Create MyCash
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        bank1.startFlow(flow)
        network.runNetwork()

        val aCorpCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }
        val bCorpCash = bCorp.transaction {
            bCorp.services.vaultService.queryBy<MyCash>().states
        }
        val refs = listOf(aCorpCash.single().ref, bCorpCash.single().ref)

        // EXIT MyCash
        val exitFlow = ExitFlow.Initiator(refs)
        val exitFuture = bank2.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()

        // MyCash is now marked as consumed
        bank1.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bank1.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        bank2.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bank2.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        bCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }
    }*/
}