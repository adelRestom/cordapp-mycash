package com.template.flow

import com.template.ExitFlow
import com.template.IssueFlow
import com.template.MoveFlow
import com.template.state.MyCash
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
    lateinit var currencyCode1: String
    lateinit var currencyCode2: String

    @Before
    fun setup() {
        network = MockNetwork(listOf("com.template.contract", "com.template.schema"))
        bank1 = network.createPartyNode()
        bank2 = network.createPartyNode()
        aCorp = network.createPartyNode()
        bCorp = network.createPartyNode()
        cCorp = network.createPartyNode()
        currencyCode1 = USD.currencyCode
        currencyCode2 = GBP.currencyCode
        listOf(aCorp, bCorp, cCorp).forEach { it.registerInitiatedFlow(IssueFlow.Acceptor::class.java) }
        listOf(aCorp, bCorp, cCorp).forEach { it.registerInitiatedFlow(MoveFlow.Acceptor::class.java) }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `ISSUE transaction is signed by the issuer and the owner`() {
        val flow = IssueFlow.Initiator(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 100L, currencyCode1)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        signedTx.verifyRequiredSignatures()
    }

    @Test
    fun `ISSUE flow records a transaction in both parties' transaction storage`() {
        val amount = 100L
        val flow = IssueFlow.Initiator(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), amount, currencyCode1)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        for (node in listOf(bank1, aCorp)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `Issue flow recorded transaction has no inputs and a single output`() {
        val amount = 100L
        val flow = IssueFlow.Initiator(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), amount, currencyCode1)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both parties' transaction storage
        for (node in listOf(bank1, aCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.size == 1)

            val recordedState = txOutputs[0].data as MyCash
            assertEquals(recordedState.issuer, bank1.info.singleIdentity())
            assertEquals(recordedState.owner, aCorp.info.singleIdentity())
            assertEquals(recordedState.amount.quantity, amount)
            assertEquals(recordedState.amount.token.product.currencyCode, currencyCode1)
        }
    }

    @Test
    fun `ISSUE flow records the correct MyCash state in the owner's vault`() {
        val amount = 100L
        val flow = IssueFlow.Initiator(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), amount, currencyCode1)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        future.getOrThrow()

        aCorp.transaction {
            val myCashs = aCorp.services.vaultService.queryBy<MyCash>().states
            assertEquals(1, myCashs.size)
            val recordedState = myCashs.single().state.data
            assertEquals(recordedState.issuer, bank1.info.singleIdentity())
            assertEquals(recordedState.owner, aCorp.info.singleIdentity())
            assertEquals(recordedState.amount.quantity, amount)
            assertEquals(recordedState.amount.token.product.currencyCode, currencyCode1)
        }
    }

    @Test
    fun `Recorded EXIT MyCash transaction has no outputs and a single input`() {
        // Create MyCash
        val amount = 100L
        val flow = IssueFlow.Initiator(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), amount, currencyCode1)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val myCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }
        val ref = myCash.single().ref
        val txHash = ref.txhash.toString()
        val txIndex = ref.index
    
        // EXIT MyCash
        val exitFlow = ExitFlow.Initiator(txHash, txIndex)
        val exitFuture = aCorp.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()
    
        // We check the recorded EXIT transaction in the owner
        val recordedTx = aCorp.services.validatedTransactions.getTransaction(exitSignedTx.id)
        val txInputs = recordedTx!!.tx.inputs
        assert(txInputs.size == 1)
        val txOutputs = recordedTx!!.tx.outputs
        assert(txOutputs.isEmpty())

        val stateRef = txInputs[0]
        val myCashStateAndRef = aCorp.services.toStateAndRef<MyCash>(stateRef)
        val recordedState = myCashStateAndRef.state.data
        assertEquals(recordedState.issuer, bank1.info.singleIdentity())
        assertEquals(recordedState.owner, aCorp.info.singleIdentity())
        assertEquals(recordedState.amount.quantity, amount)
        assertEquals(recordedState.amount.token.product.currencyCode, currencyCode1)
    }
    
    @Test
    fun `EXIT flow consumes MyCash in both parties' vaults`() {
        // Create MyCash
        val amount = 100L
        val flow = IssueFlow.Initiator(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), amount, currencyCode1)
        bank1.startFlow(flow)
        network.runNetwork()
        val myCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }
        val ref = myCash.single().ref
        val txHash = ref.txhash.toString()
        val txIndex = ref.index

        // EXIT MyCash
        val exitFlow = ExitFlow.Initiator(txHash, txIndex)
        aCorp.startFlow(exitFlow)
        network.runNetwork()
    
        // MyCash is now marked as consumed in the owner
        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }
    }
}