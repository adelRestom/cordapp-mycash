package com.template.contract

import com.template.state.MyCash
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

class MyCashContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val MyCash_Contract_ID = "com.template.contract.MyCashContract"
    }

    // A transaction is valid if the verify() function of the contract of all the transaction's input and output states
    // does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        // Verification logic goes here.
        // Require a single command per transaction
        val command = tx.commands.requireSingleCommand<MyCashContract.Commands>()
        val inputs = tx.inputsOfType<MyCash>()
        val outputs = tx.outputsOfType<MyCash>()

        when (command.value) {
            is Commands.Issue -> {
                requireThat {
                    // Input/output
                    "No inputs should be consumed during MyCash ISSUE." using (inputs.isEmpty())
                    "At least one output should be created." using (outputs.isNotEmpty())

                    // Signatures
                    "Issuers must sign MyCash ISSUE transaction." using command.signers.containsAll(outputs.map { it.issuer.owningKey })

                    // Business logic
                    "Issuers and owners cannot be the same entity." using outputs.filter { it.issuer == it.owner }.isEmpty()
                }
            }

            is Commands.Move -> {
                requireThat {
                    // Input/output
                    "One or more inputs should be consumed during MyCash MOVE." using (inputs.isNotEmpty())
                    "At least one output should be created." using (outputs.isNotEmpty())

                    // Signatures
                    "Previous owners must sign MyCash MOVE transaction." using command.signers.containsAll(inputs.map { it.owner.owningKey })

                    // Business logic
                    val inOut = listOf(inputs, outputs).flatMap { it }
                    val issuers = inOut.map { it.issuer }.distinct()
                    val currencyCodes = inOut.map { it.amount.token.product.currencyCode }.distinct()
                    for (issuer in issuers) {
                        for (currencyCode in currencyCodes) {
                            val inputSum = inputs.filter { it.issuer == issuer && it.amount.token.product.currencyCode == currencyCode }.map { it.amount.quantity }.sum()
                            val outputSum = outputs.filter { it.issuer == issuer && it.amount.token.product.currencyCode == currencyCode }.map { it.amount.quantity }.sum()
                            "Inputs total amount (by issuer/currency code) must be equal to outputs total amount (by issuer/currency code)." using (inputSum == outputSum)
                        }
                    }
                }
            }

            is Commands.Exit -> {
                requireThat {
                    // Input/output
                    "One or more inputs should be consumed during MyCash EXIT." using (inputs.isNotEmpty())
                    "There shouldn't be any outputs created." using (outputs.isEmpty())

                    // Signatures
                    "Owners must sign MyCash EXIT transaction." using command.signers.containsAll(inputs.flatMap { it.exitKeys }.distinct())
                }
            }

            else -> throw IllegalArgumentException("Unrecognised MyCash command")
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