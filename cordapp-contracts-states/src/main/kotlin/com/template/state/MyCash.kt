package com.template.state

import com.template.contract.MyCashContract
import com.template.schema.MyCashSchemaV1
import net.corda.core.contracts.Amount
import net.corda.core.contracts.CommandAndState
import net.corda.core.contracts.FungibleAsset
import net.corda.core.contracts.Issued
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.utilities.OpaqueBytes
import java.util.*

data class MyCash(val issuer: AbstractParty,
                  override val owner: AbstractParty,
                  override val amount: Amount<Issued<Currency>>):
        FungibleAsset<Currency>, QueryableState {

    init{
        require(amount.quantity > 0L) { " MyCash amount cannot be zero " }
        require(issuer == amount.token.issuer.party) { " MyCash issuer cannot be different from Currency issuer " }
    }

    constructor(issuer: AbstractParty,
                owner: AbstractParty,
                amount: Long,
                currencyCode: String):
            this(issuer, owner, Amount(amount, Issued(issuer.ref(OpaqueBytes.of(0x01)), Currency.getInstance(currencyCode))))

    // Owner should be aware of this state
    override val participants
        get() = listOf(owner)

    // Owner must sign exit command to destroy the cash amount (i.e. move it off-ledger)
    override val exitKeys
        get() = listOf(owner.owningKey)

    override fun withNewOwner(newOwner: AbstractParty): CommandAndState {
        val updatedCash = copy(owner = newOwner)
        return CommandAndState(MyCashContract.Commands.Move(), updatedCash)
    }

    override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Currency>>, newOwner: AbstractParty): FungibleAsset<Currency> {
        val updatedCash = copy(owner = newOwner, amount = amount.copy(newAmount.quantity))
        return updatedCash
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MyCashSchemaV1)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is MyCashSchemaV1 -> MyCashSchemaV1.PersistentMyCash(
                    this.issuer.nameOrNull().toString(),
                    this.owner.nameOrNull().toString(),
                    this.amount.quantity,
                    this.amount.token.product.currencyCode
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
}