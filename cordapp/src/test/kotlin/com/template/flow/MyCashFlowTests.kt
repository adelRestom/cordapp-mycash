package com.template.flow

import com.template.*
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
import kotlin.test.assertNotEquals

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
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(MoveFlow.Acceptor::class.java) }
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(SignFinalize.Acceptor::class.java) }
        listOf(aCorp, bCorp, cCorp, bank1, bank2).forEach { it.registerInitiatedFlow(RegisterIdentityFlow.Receive::class.java) }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
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
    fun `ISSUE flow recorded transaction has no inputs and one or more MyCash outputs`() {
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
    fun `Anonymous ISSUE transaction is signed by anonymous parties`() {
        // Issue anonymously
        val flow = IssueFlow.Initiator(outputs = listOf(myCash1, myCash2), anonymous = true)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        for (node in listOf(aCorp, bCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            // Transaction must not be signed with the known public key of the node
            assert(!recordedTx!!.sigs.map { it.by }.contains(node.info.singleIdentity().owningKey))
        }
    }

    @Test
    fun `Known ISSUE transaction is signed by known parties`() {
        val flow = IssueFlow.Initiator(outputs = listOf(myCash1, myCash2), anonymous = false)
        val future = bank1.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        for (node in listOf(aCorp, bCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            // Transaction must be signed with the known public key of the node
            assert(recordedTx!!.sigs.map { it.by }.contains(node.info.singleIdentity().owningKey))
        }
    }

    @Test
    fun `Anonymous ISSUE flow records anonymous identities in the new states`() {
        // Issue anonymously
        val knownFlow = IssueFlow.Initiator(listOf(myCash1, myCash2), anonymous = true)
        bank1.startFlow(knownFlow)
        network.runNetwork()

        aCorp.transaction {
            val myCashStates = aCorp.services.vaultService.queryBy<MyCash>().states
            val recordedState = myCashStates.single().state.data
            assertNotEquals(bank1.info.singleIdentity(), recordedState.issuer)
            assertNotEquals(aCorp.info.singleIdentity(), recordedState.owner)
            assertEquals(amount1, recordedState.amount.quantity)
            assertEquals(currencyCode1, recordedState.amount.token.product.currencyCode)
        }

        bCorp.transaction {
            val myCashStates = bCorp.services.vaultService.queryBy<MyCash>().states
            val recordedState = myCashStates.single().state.data
            assertNotEquals(bank2.info.singleIdentity(), recordedState.issuer)
            assertNotEquals(bCorp.info.singleIdentity(), recordedState.owner)
            assertEquals(amount2, recordedState.amount.quantity)
            assertEquals(currencyCode2, recordedState.amount.token.product.currencyCode)
        }
    }

    @Test
    fun `Recorded EXIT MyCash transaction has no outputs and one or more MyCash inputs (anonymous and known)`() {
        // Create MyCash anonymously
        val anonymousFlow = IssueFlow.Initiator(listOf(myCash1), anonymous = true)
        bank1.startFlow(anonymousFlow)
        network.runNetwork()

        val aCorpCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }

        // EXIT MyCash
        val anonymousExitFlow = ExitFlow.Initiator(listOf(aCorpCash.single().ref))
        val anonymousExitFuture = bank1.startFlow(anonymousExitFlow)
        network.runNetwork()
        val anonymousExitSignedTx = anonymousExitFuture.getOrThrow()

        // We check the recorded transaction in transaction storage
        val recordedTx = aCorp.services.validatedTransactions.getTransaction(anonymousExitSignedTx.id)
        val txOutputs = recordedTx!!.tx.outputs
        assert(txOutputs.isEmpty())

        val txInputs = recordedTx!!.tx.inputs.map { aCorp.services.toStateAndRef<MyCash>(it) }
        assert(txInputs.isNotEmpty())
        // myCash1 was issued anonymously so the owner and issuer
        // will be replaced with their respective secret identities
        assert(!txInputs.map { it.state.data }.contains(myCash1))
        // myCash1 EXIT was signed with anonymous parties
        assert(!recordedTx.sigs.map { it.by }.contains(aCorp.info.singleIdentity().owningKey))

        // Create MyCash with well known parties
        val knownFlow = IssueFlow.Initiator(listOf(myCash2), anonymous = false)
        bank1.startFlow(knownFlow)
        network.runNetwork()

        val bCorpCash = bCorp.transaction {
            bCorp.services.vaultService.queryBy<MyCash>().states
        }

        // Exit MyCash
        val knownExitFlow = ExitFlow.Initiator(listOf(bCorpCash.single().ref))
        val knownExitFuture = bank1.startFlow(knownExitFlow)
        network.runNetwork()
        val knownExitSignedTx = knownExitFuture.getOrThrow()

        // We check the recorded transaction in transaction storage
        val knownRecordedTx = bCorp.services.validatedTransactions.getTransaction(knownExitSignedTx.id)
        val knownTxOutputs = knownRecordedTx!!.tx.outputs
        assert(knownTxOutputs.isEmpty())

        val knownTxInputs = knownRecordedTx!!.tx.inputs.map { bCorp.services.toStateAndRef<MyCash>(it) }
        assert(knownTxInputs.isNotEmpty())
        // myCash2 was issued with well known parties
        assert(knownTxInputs.map { it.state.data }.contains(myCash2))
        // myCash2 EXIT was signed with well known parties
        assert(knownRecordedTx.sigs.map { it.by }.contains(bCorp.info.singleIdentity().owningKey))

    }

    @Test
    fun `EXIT flow consumes (a mix of well known and anonymous) MyCash states in all participants' vaults`() {
        // Create MyCash anonymously
        val anonymousFlow = IssueFlow.Initiator(listOf(myCash1), anonymous = true)
        bank1.startFlow(anonymousFlow)
        network.runNetwork()

        // Create MyCash with well known parties
        val flow = IssueFlow.Initiator(listOf(myCash1), anonymous = false)
        bank1.startFlow(flow)
        network.runNetwork()

        val aCorpCash = aCorp.transaction {
            aCorp.services.vaultService.queryBy<MyCash>().states
        }

        // EXIT MyCash
        val exitFlow = ExitFlow.Initiator(aCorpCash.map { it.ref })
        val exitFuture = bank1.startFlow(exitFlow)
        network.runNetwork()
        val exitSignedTx = exitFuture.getOrThrow()

        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCash = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            assert(myCash.isEmpty())
        }
    }

    @Test
    fun `Anonymous Recorded MOVE MyCash transaction has one or more MyCash inputs and one or more MyCash outputs`() {
        // Anonymously, aCorp will have (100 USD from bank1, 50 GBP from bank1)
        val a1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 100L, "USD")
        val a2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 50L, "GBP")
        val anonymousFlow = IssueFlow.Initiator(listOf(a1, a2), anonymous = true)
        //anonymousFlow.progressTracker.changes.subscribe { println("---ISSUE PROGRESS TRACKER--->$it") }
        bank1.startFlow(anonymousFlow)
        network.runNetwork()

        // Anonymously, aCorp will move (10+28 USD from bank1, 35 GBP from bank1)
        val am1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 10L, "USD")
        val am2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 28L, "USD")
        val am3 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 35L, "GBP")
        // MOVE MyCash to cCorp
        val anonymousMoveFlow = MoveFlow.Initiator(listOf(am1, am2, am3), cCorp.info.singleIdentity(),
                anonymous = true)
        //anonymousMoveFlow.progressTracker.changes.subscribe { println("---MOVE PROGRESS TRACKER--->$it") }
        val anonymousMoveFuture = aCorp.startFlow(anonymousMoveFlow)
        network.runNetwork()
        val anonymousMoveSignedTx = anonymousMoveFuture.getOrThrow()

        // Consolidated amounts that moved to cCorp
        val co1 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 38L, "USD")
        val co2 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 35L, "GBP")
        // Change amounts
        val ch1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 62L, "USD")
        val ch2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 15L, "GBP")

        // We check the recorded anonymous transaction in cCorp transaction storage
        val anonymousRecordedTx = cCorp.services.validatedTransactions.getTransaction(anonymousMoveSignedTx.id)
        val atxInputs = cCorp.transaction {
            anonymousMoveSignedTx.tx.inputs.map { cCorp.services.toStateAndRef<MyCash>(it) }
        }
        assert(atxInputs.isNotEmpty())
        val anonymousTxOutputs = anonymousRecordedTx!!.tx.outputs.filter { it.data is MyCash }
        assert(anonymousTxOutputs.isNotEmpty())
        val filteredResults1 = anonymousTxOutputs.map { it.data }.filter {
            it is MyCash &&
                    // Compare issuer, owner, quantity, and currency
                    (it.compareQuantityAndCurrency(co1) &&
                            cCorp.services.identityService.wellKnownPartyFromAnonymous(it.issuer) == co1.issuer &&
                            cCorp.services.identityService.wellKnownPartyFromAnonymous(it.owner) == co1.owner
                            )
        }
        // Transaction contains co1
        assert(filteredResults1.isNotEmpty())

        val filteredResults2 = anonymousTxOutputs.map { it.data }.filter {
            it is MyCash &&
                    // Compare issuer, owner, quantity, and currency
                    (it.compareQuantityAndCurrency(co2) &&
                            cCorp.services.identityService.wellKnownPartyFromAnonymous(it.issuer) == co2.issuer &&
                            cCorp.services.identityService.wellKnownPartyFromAnonymous(it.owner) == co2.owner
                            )
        }
        // Transaction contains co2
        assert(filteredResults2.isNotEmpty())
    }

    @Test
    fun `Known Recorded MOVE MyCash transaction has one or more MyCash inputs and one or more MyCash outputs`() {
        // bCorp will have (100 USD from bank1, 50 GBP from bank1, 100 USD from bank2)
        val b1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val b2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 50L, "GBP")
        val b3 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val flow = IssueFlow.Initiator(listOf(b1, b2, b3), anonymous = false)
        bank1.startFlow(flow)
        network.runNetwork()

        // bCorp will move (25+75 USD from bank1, 13 GBP from bank1, 20 USD from bank2)
        val bm1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 25L, "USD")
        val bm2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 75L, "USD")
        val bm3 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 13L, "GBP")
        val bm4 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 20L, "USD")
        // MOVE MyCash to cCorp
        val moveFlow = MoveFlow.Initiator(listOf(bm1, bm2, bm3, bm4), cCorp.info.singleIdentity(),
                anonymous = false)
        val moveFuture = bCorp.startFlow(moveFlow)
        network.runNetwork()
        val moveSignedTx = moveFuture.getOrThrow()

        // Consolidated amounts that moved to cCorp
        val co3 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 100L, "USD")
        val co4 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 13L, "GBP")
        val co5 = MyCash(bank2.info.singleIdentity(), cCorp.info.singleIdentity(), 20L, "USD")
        // Change amounts
        val ch3 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 37L, "GBP")
        val ch4 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 80L, "USD")

        // We check the recorded well known transaction in cCorp transaction storage
        val recordedTx = cCorp.services.validatedTransactions.getTransaction(moveSignedTx.id)
        val txInputs = recordedTx!!.tx.inputs.map { cCorp.services.toStateAndRef<MyCash>(it) }
        assert(txInputs.isNotEmpty())
        val txOutputs = recordedTx.tx.outputs.filter { it.data is MyCash }
        assert(txOutputs.isNotEmpty())
        // Transaction contains all well known consolidated moved amounts and change amounts
        assert(txOutputs.map { it.data }.containsAll(listOf(co3, co4, co5, ch3, ch4)))
    }

    @Test
    fun `Known MOVE flow creates new MyCash states in old and new owners' vaults`() {
        // Create MyCash
        // aCorp will have (100 USD from bank1, 50 GBP from bank1)
        val a1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 100L, "USD")
        val a2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 50L, "GBP")
        // bCorp will have (100 USD from bank1, 50 GBP from bank1, 100 USD from bank2)
        val b1 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val b2 = MyCash(bank1.info.singleIdentity(), bCorp.info.singleIdentity(), 50L, "GBP")
        val b3 = MyCash(bank2.info.singleIdentity(), bCorp.info.singleIdentity(), 100L, "USD")
        val flow = IssueFlow.Initiator(listOf(a1, a2, b1, b2, b3), anonymous = false)
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
        val moveFlow = MoveFlow.Initiator(listOf(am1, am2, am3, bm1, bm2, bm3, bm4),
                cCorp.info.singleIdentity(), anonymous = false)
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

        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCashList = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            // aCorp had 100 USD from bank1; it sent 10+28 of them; so the change is 100-38 = 62
            assert(myCashList.map { it.state.data }.contains(ch1))
            // aCorp had 50 GBP from bank1; it sent 15 of them; so the change is 50-35 = 15
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
    }

    @Test
    fun `Anonymous MOVE flow creates new MyCash states in old and new owners' vaults`() {
        // Anonymously Create MyCash
        // aCorp will have (100 USD from bank1, 50 GBP from bank1)
        val a1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 100L, "USD")
        val a2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 50L, "GBP")
        val flow = IssueFlow.Initiator(listOf(a1, a2), anonymous = true)
        bank1.startFlow(flow)
        network.runNetwork()

        // aCorp will move (10+28 USD from bank1, 35 GBP from bank1)
        val am1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 10L, "USD")
        val am2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 28L, "USD")
        val am3 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 35L, "GBP")

        // Anonymously MOVE MyCash to cCorp
        val moveFlow = MoveFlow.Initiator(listOf(am1, am2, am3),
                cCorp.info.singleIdentity(), anonymous = true)
        val moveFuture = aCorp.startFlow(moveFlow)
        network.runNetwork()
        val moveSignedTx = moveFuture.getOrThrow()

        // Consolidated amounts that moved to cCorp
        val co1 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 38L, "USD")
        val co2 = MyCash(bank1.info.singleIdentity(), cCorp.info.singleIdentity(), 35L, "GBP")
        // Change amounts
        val ch1 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 62L, "USD")
        val ch2 = MyCash(bank1.info.singleIdentity(), aCorp.info.singleIdentity(), 15L, "GBP")

        aCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCashList = aCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            // aCorp had 100 USD from bank1; it sent 10+28 of them; so the change is 100-38 = 62
            val filteredResults1 = myCashList.map { it.state.data }.filter {
                // Compare issuer, owner, quantity, and currency
                it.compareQuantityAndCurrency(ch1) &&
                        aCorp.services.identityService.wellKnownPartyFromAnonymous(it.issuer) == ch1.issuer &&
                        aCorp.services.identityService.wellKnownPartyFromAnonymous(it.owner) == ch1.owner
            }
            // Transaction contains ch1
            assert(filteredResults1.isNotEmpty())

            // aCorp had 50 GBP from bank1; it sent 15 of them; so the change is 50-35 = 15
            val filteredResults2 = myCashList.map { it.state.data }.filter {
                // Compare issuer, owner, quantity, and currency
                it.compareQuantityAndCurrency(ch2) &&
                        aCorp.services.identityService.wellKnownPartyFromAnonymous(it.issuer) == ch2.issuer &&
                        aCorp.services.identityService.wellKnownPartyFromAnonymous(it.owner) == ch2.owner
            }
            // Transaction contains ch2
            assert(filteredResults2.isNotEmpty())
        }

        cCorp.transaction {
            val unconsumedCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val myCashList = cCorp.services.vaultService.queryBy<MyCash>(unconsumedCriteria).states
            // cCorp received 10+28 USD from bank1/aCorp
            val filteredResults1 = myCashList.map { it.state.data }.filter {
                // Compare issuer, owner, quantity, and currency
                it.compareQuantityAndCurrency(co1) &&
                        cCorp.services.identityService.wellKnownPartyFromAnonymous(it.issuer) == co1.issuer &&
                        cCorp.services.identityService.wellKnownPartyFromAnonymous(it.owner) == co1.owner
            }
            // Transaction contains co1
            assert(filteredResults1.isNotEmpty())

            // cCorp received 35 GBP from bank1/aCorp
            val filteredResults2 = myCashList.map { it.state.data }.filter {
                // Compare issuer, owner, quantity, and currency
                it.compareQuantityAndCurrency(co2) &&
                        cCorp.services.identityService.wellKnownPartyFromAnonymous(it.issuer) == co2.issuer &&
                        cCorp.services.identityService.wellKnownPartyFromAnonymous(it.owner) == co2.owner
            }
            // Transaction contains co2
            assert(filteredResults2.isNotEmpty())
        }
    }
}