package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.identity.Party
import net.corda.core.identity.AnonymousParty
import java.security.PublicKey
import java.util.*

// ************
// * Contract *
// ************
class MyCashContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "com.template.MyCashContract"
    }
    
    // A transaction is valid if the verify() function of the contract of all the transaction's input and output states
    // does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        // Verification logic goes here.
        // Require a single command per transaction
        val command = tx.commands.requireSingleCommand<MyCashContract.Commands>()

        when (command.value) {
            is Commands.Issue -> {
                requireThat {
                    // Generic constraints around the IOU transaction.
                    "No inputs should be consumed when issuing new cash." using (tx.inputs.isEmpty())
                    "At least one output state should be created." using (tx.outputs.isNotEmpty())
                    val outputs = tx.outputsOfType<MyCash>()
                    "The issuer and the owner cannot be the same entity." using outputs.filter { it.issuer == it.owner }.isNotEmpty()
                    "Only the issuer can create new cash." using command.signers.containsAll(outputs.map { it.issuer.owningKey })
                }
            }

            is Commands.Move -> {
                requireThat {
                    // Generic constraints around the IOU transaction.
                    "One or more inputs should be consumed when moving cash." using (tx.inputs.isNotEmpty())
                    "At least one output state should be created." using (tx.outputs.isNotEmpty())
                    val outputs = tx.outputsOfType<MyCash>()
                    "Old owner must sign the move." using command.signers.containsAll(outputs.map { it.oldOwner.owningKey })
                }
            }

            is Commands.Exit -> {
                requireThat {
                    // Generic constraints around the IOU transaction.
                    "One or more inputs should be consumed when destroying cash." using (tx.inputs.isNotEmpty())
                    "There should be no outputs." using (tx.outputs.isEmpty())
                    val inputs = tx.inputsOfType<MyCash>()
                    "Issuer and Owners must sign the exit command." using command.signers.containsAll(inputs.map { it.exitKeys[0]; it.exitKeys[1] })
                }
            }

            else -> throw IllegalArgumentException("Unrecognised command")
        }
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        // Issue new cash
        class Issue : Commands
        // Move cash to a new owner
        class Move : Commands
        // Move cash off-ledger
        class Exit : Commands
    }
}

// *********
// * State *
// *********
data class MyCash(val issuer: AbstractParty,
                  override val owner: AbstractParty,
                  override var amount: Amount<Issued<Currency>>) :
        FungibleAsset<Currency>{

    var oldOwner: AbstractParty = owner

    // Issuer and owner should be aware of this state
    override var participants = listOf(issuer, owner)

    // Issuer and owner must sign exit command to destroy the cash amount (i.e. move it off-ledger)
    override var exitKeys = listOf(issuer.owningKey, owner.owningKey)

    override fun withNewOwner(newOwner: AbstractParty): CommandAndState {
        val updatedCash = copy(owner = newOwner)
        updatedCash.oldOwner = owner
        updatedCash.participants = listOf(issuer, owner)
        updatedCash.exitKeys = listOf(issuer.owningKey, owner.owningKey)
        return CommandAndState(MyCashContract.Commands.Move(), updatedCash)
    }

    override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Currency>>, newOwner: AbstractParty): FungibleAsset<Currency> {
        val updatedCash = copy(owner = newOwner, amount = newAmount)
        updatedCash.oldOwner = owner
        updatedCash.participants = listOf(issuer, owner)
        updatedCash.exitKeys = listOf(issuer.owningKey, owner.owningKey)
        return updatedCash
    }
}
