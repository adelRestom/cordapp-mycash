package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

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
                    "Only one output state should be created." using (tx.outputs.size == 1)
                    val out = tx.outputsOfType<MyCash>().single()
                    "The issuer and the owner cannot be the same entity." using (out.issuer != out.owner)
                    "Only the issuer can create new cash." using (command.signers.contains(out.issuer))

                    // MyCash-specific constraints.
                    "The cash value must be non-negative." using (out.value > 0)
                }
            }

            is Commands.Move -> {
                requireThat {
                    // Generic constraints around the IOU transaction.
                    "One or more inputs should be consumed when moving cash." using (tx.inputs.isNotEmpty())
                    "Only one output state should be created." using (tx.outputs.size == 1)
                    val out = tx.outputsOfType<MyCash>().single()
                    "The new owner and the old owner cannot be the same entity." using (out.oldOwner != out.owner)
                    "Old owner must sign the move." using (command.signers.contains(out.oldOwner))

                    // MyCash-specific constraints.
                    "The cash value must be non-negative." using (out.value > 0)
                }
            }

            is Commands.Exit -> {
                requireThat {
                    // Generic constraints around the IOU transaction.
                    "One or more inputs should be consumed when destroying cash." using (tx.inputs.isNotEmpty())
                    "There should be no outputs." using (tx.outputs.isEmpty())
                    val input = tx.inputsOfType<MyCash>().single()
                    "Both issuer and owner must sign the exit command." using command.signers.containsAll(input.exitKeys.map { it.owningKey })
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
data class MyCash(val issuer: party,
                  override var owner: party,
                  override var amount: Long) :
        FungibleAsset<Amount<Issued<Currency>>> {

    var oldOwner: party = AnonymousParty(NullKeys.NullPublicKey)

    // Issuer and owner should be aware of this state
    override val participants = listOf(issuer, owner)

    // Issuer and owner must sign the exit command to destroy cash amount (i.e. move it off-ledger)
    override val exitKeys = listOf(issuer, owner)

    override fun withNewOwner(newOwner: AbstractParty): CommandAndState {
        copy(oldOwner = owner, owner = newOwner)
    }

    override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Amount<Issued<>>>>, newOwner: AbstractParty): FungibleAsset<Amount<Issued<>>> {
        copy(oldOwner = owner, owner = newOwner, amount = newAmount)
    }
}
