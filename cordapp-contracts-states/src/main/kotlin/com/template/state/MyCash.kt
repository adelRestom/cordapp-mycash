package com.template.state

import com.template.contract.MyCashContract
import com.template.schema.MyCashSchemaV1
import net.corda.core.contracts.Amount
import net.corda.core.contracts.CommandAndState
import net.corda.core.contracts.FungibleAsset
import net.corda.core.contracts.Issued
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.utilities.OpaqueBytes
import java.util.*

data class MyCash(override val owner: Party,
                  override val amount: Amount<Issued<Currency>>):
        FungibleAsset<Currency>, QueryableState {

    constructor(issuer: Party,
                owner: Party,
                amount: Long,
                currencyCode: String):
            this(owner, Amount(amount, Issued(issuer.ref(OpaqueBytes.of(0x01)), Currency.getInstance(currencyCode)))) {
        require(amount > 0L) { " MyCash amount cannot be zero " }
    }

    val issuer
        get() = amount.token.issuer.party as? Party ?: throw Exception("Issuer is not of type Party")

    // Owner should be aware of this state
    override val participants
        get() = listOf(owner)

    // Issuer and owner must sign EXIT command
    override val exitKeys
        get() = setOf(issuer.owningKey, owner.owningKey)

    override fun withNewOwner(newOwner: AbstractParty): CommandAndState {
        if (newOwner is Party) return CommandAndState(MyCashContract.Commands.Move(), copy(owner = newOwner))
        else {
            throw Exception("New owner is not of type Party")
        }
    }

    override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Currency>>, newOwner: AbstractParty): FungibleAsset<Currency> {
        if (newOwner is Party) return copy(owner = newOwner, amount = amount.copy(newAmount.quantity))
        else {
            throw Exception("New owner is not of type Party")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MyCashSchemaV1)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is MyCashSchemaV1 -> MyCashSchemaV1.PersistentMyCash(
                    this.issuer.name.toString(),
                    this.owner.name.toString(),
                    this.amount.quantity,
                    this.amount.token.product.currencyCode
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
}