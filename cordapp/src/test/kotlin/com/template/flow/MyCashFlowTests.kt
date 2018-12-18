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
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(MoveFlow.SignFinalizeAcceptor::class.java) }
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
    fun `ISSUE flow records a transaction in participants' transaction storage`() {
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        for (node in listOf(aCorp, bCorp)) {
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
        for (node in listOf(aCorp, bCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            assert(recordedTx!!.tx.inputs.isEmpty())
            val txOutputs = recordedTx!!.tx.outputs
            val myCashOutputs = txOutputs.filter {
                val myCash = it.data as? MyCash
                if (myCash != null)
                    myCash.owner == node.info.singleIdentity()
                else
                    false
            }
            assert(myCashOutputs.isNotEmpty())
            if (node == aCorp)
                assert(myCashOutputs.map { it.data }.contains(myCash1))
            else
                assert(myCashOutputs.map { it.data }.contains(myCash2))
        }
    }

    @Test
    fun `ISSUE flow records the correct MyCash state in participants' vaults`() {
        val flow = IssueFlow.Initiator(listOf(myCash1, myCash2))
        bank1.startFlow(flow)
        network.runNetwork()

        aCorp.transaction {
            val myCashStates = aCorp.services.vaultService.queryBy<MyCash>().states
            assertEquals(myCashStates.size, 1)
            val recordedState = myCashStates.single().state.data
            assertEquals(recordedState.issuer, bank1.info.singleIdentity())
            assertEquals(recordedState.owner, aCorp.info.singleIdentity())
            assertEquals(recordedState.amount.quantity, amount1)
            assertEquals(recordedState.amount.token.product.currencyCode, currencyCode1)
        }

        bCorp.transaction {
            val myCashStates = bCorp.services.vaultService.queryBy<MyCash>().states
            assertEquals(myCashStates.size, 1)
            val recordedState = myCashStates.single().state.data
            assertEquals(recordedState.issuer, bank2.info.singleIdentity())
            assertEquals(recordedState.owner, bCorp.info.singleIdentity())
            assertEquals(recordedState.amount.quantity, amount2)
            assertEquals(recordedState.amount.token.product.currencyCode, currencyCode2)
        }
    }

    @Test
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
        val exitFuture = bank1.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()

        // We check the recorded transaction in all participants' transaction storage
        for (node in listOf(aCorp, bCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(exitSignedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.isEmpty())

            val txInputs = recordedTx!!.tx.inputs.map { node.services.toStateAndRef<MyCash>(it) }
            assert(txInputs.isNotEmpty())
            if (node == aCorp)
                assert(txInputs.map { it.state.data }.contains(myCash1))
            else
                assert(txInputs.map { it.state.data }.contains(myCash2))
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
        val exitFuture = bank1.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()

        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }

        bCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = bCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }
    }*/

    @Test
    fun `Recorded MOVE MyCash transaction has one or more MyCash inputs and one or more MyCash outputs`() {
        // Create MyCash
        // aCorp will have (100 USD from bank1, 50 GBP from bank1)
        val a1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 100L, "USD")
        val a2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 50L, "GBP")
        // bCorp will have (100 USD from bank1, 50 GBP from bank1, 100 USD from bank2)
        val b1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val b2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 50L, "GBP")
        val b3 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val flow = IssueFlow.Initiator(listOf(a1, a2, b1, b2, b3))
        bank1.startFlow(flow)
        network.runNetwork()

        // aCorp will move (10+28 USD from bank1, 35 GBP from bank1)
        val am1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 10L, "USD")
        val am2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 28L, "USD")
        val am3 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 35L, "GBP")
        // bCorp will move (25+75 USD from bank1, 13 GBP from bank1, 20 USD from bank2)
        val bm1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 25L, "USD")
        val bm2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 75L, "USD")
        val bm3 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 13L, "GBP")
        val bm4 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 20L, "USD")

        // MOVE MyCash to cCorp
        val moveFlow = MoveFlow.Initiator(listOf(am1, am2, am3, bm1, bm2, bm3, bm4), cCorp.info.singleIdentity())
        val moveFuture = aCorp.startFlow(moveFlow)
        network.runNetwork()
        val moveSignedTx = moveFuture.getOrThrow()

        // Consolidated amounts that moved to cCorp
        val co1 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 38L, "USD")
        val co2 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 35L, "GBP")
        val co3 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 100L, "USD")
        val co4 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 13L, "GBP")
        val co5 = MyCash(bank2.info.singleIdentity(), cCorp.info.singleIdentity(), 20L, "USD")
        // Change amounts
        val ch1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 62L, "USD")
        val ch2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 15L, "GBP")
        val ch3 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 37L, "GBP")
        val ch4 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 80L, "USD")


        // We check the recorded transaction in all participants' transaction storage
        for (node in listOf(cCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(moveSignedTx.id)
            val txInputs = recordedTx!!.tx.inputs.map { node.services.toStateAndRef<MyCash>(it) }
            assert(txInputs.isNotEmpty())

            val txOutputs = recordedTx.tx.outputs.filter { it.data is MyCash }
            assert(txOutputs.isNotEmpty())
            assert(txOutputs.map { it.data }.containsAll(listOf(co1, co2, co3, co4, co5, ch1, ch2, ch3, ch4)))
        }
    }

    /*@Test
    fun `EXIT flow creates new MyCash states in old and new owners' vaults`() {
        // Create MyCash
        // aCorp will have (100 USD from bank1, 50 GBP from bank1)
        val a1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 100L, "USD")
        val a2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 50L, "GBP")
        // bCorp will have (100 USD from bank1, 50 GBP from bank1, 100 USD from bank2)
        val b1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val b2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 50L, "GBP")
        val b3 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val flow = IssueFlow.Initiator(listOf(a1, a2, b1, b2, b3))
        bank1.startFlow(flow)
        network.runNetwork()

        // aCorp will move (10+28 USD from bank1, 35 GBP from bank1)
        val am1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 10L, "USD")
        val am2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 28L, "USD")
        val am3 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 35L, "GBP")
        // bCorp will move (25+75 USD from bank1, 13 GBP from bank1, 20 USD from bank2)
        val bm1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 25L, "USD")
        val bm2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 75L, "USD")
        val bm3 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 13L, "GBP")
        val bm4 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 20L, "USD")

        // MOVE MyCash to cCorp
        val moveFlow = MoveFlow.Initiator(listOf(am1, am2, am3, bm1, bm2, bm3, bm4), cCorp.info.singleIdentity())
        moveFlow.progressTracker.changes.subscribe { println("---PROGRESS TRACKER--->$it") }
        val moveFuture = aCorp.startFlow(moveFlow)
        network.runNetwork()
        val moveSignedTx = moveFuture.getOrThrow()

        // Consolidated amounts that moved to cCorp
        val co1 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 38L, "USD")
        val co2 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 35L, "GBP")
        val co3 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 100L, "USD")
        val co4 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 13L, "GBP")
        val co5 = MyCash(bank2.info.singleIdentity(), cCorp.info.singleIdentity(), 20L, "USD")
        // Change amounts
        val ch1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 62L, "USD")
        val ch2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 15L, "GBP")
        val ch3 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 37L, "GBP")
        val ch4 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 80L, "USD")

        // aCorp had 100 USD from bank1 and sent
        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCashList = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            // aCorp had 100 USD from bank1; it sent 10+28 of them; so the change is 100-38 = 62
            assert(myCashList.map { it.state.data }.contains(ch1))
            // aCorp had 50 GBP from bank1; it sent 15 of them; so the change is 50-15 = 35
            assert(myCashList.map { it.state.data }.contains(ch2))
        }

        bCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCashList = bCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            // bCorp had 100 USD from bank1; it sent 25+75 of them; so the change is 100-100 = 0
            // bCorp had 50 GBP from bank1; it sent 13 of them; so the change is 50-13 = 37
            assert(myCashList.map { it.state.data }.contains(ch3))
            // bCorp had 100 USD from bank2; it sent 20 of them; so the change is 100-20 = 80
            assert(myCashList.map { it.state.data }.contains(ch4))
        }

        cCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCashList = cCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            // cCorp received 10+28 USD from bank1/aCorp
            assert(myCashList.map { it.state.data }.contains(co1))
            // cCorp received 35 GBP from bank1/aCorp
            assert(myCashList.map { it.state.data }.contains(co2))
            // cCorp received 100 USD from bank1/bCorp
            assert(myCashList.map { it.state.data }.contains(co3))
            // cCorp received 13 GBP from bank1/bCorp
            assert(myCashList.map { it.state.data }.contains(co4))
            // cCorp received 20 USD from bank2/bCorp
            assert(myCashList.map { it.state.data }.contains(co5))
        }
    }*/
}