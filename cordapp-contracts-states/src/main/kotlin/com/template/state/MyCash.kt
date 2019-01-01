package com.template.state

import com.template.contract.MyCashContract
import com.template.schema.MyCashSchemaV1
import net.corda.core.contracts.Amount
import net.corda.core.contracts.CommandAndState
import net.corda.core.contracts.FungibleAsset
import net.corda.core.contracts.Issued
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.utilities.OpaqueBytes
import java.util.*

data class MyCash(override val owner: AbstractParty,
                  override val amount: Amount<Issued<Currency>>):
        FungibleAsset<Currency>, QueryableState {

    init{
        require(amount.quantity > 0L) { " MyCash amount cannot be zero " }
    }

    constructor(issuer: AbstractParty,
                owner: AbstractParty,
                amount: Long,
                currencyCode: String):
            this(owner, Amount(amount, Issued(issuer.ref(OpaqueBytes.of(0x01)), Currency.getInstance(currencyCode))))

    val issuer
        get() = amount.token.issuer.party

    // Owner should be aware of this state
    override val participants
        get() = listOf(owner)

    // Issuer and owner must sign EXIT command
    override val exitKeys
        get() = setOf(issuer.owningKey, owner.owningKey)

    override fun withNewOwner(newOwner: AbstractParty): CommandAndState {
        return CommandAndState(MyCashContract.Commands.Move(), copy(owner = newOwner))
    }

    override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Currency>>, newOwner: AbstractParty): FungibleAsset<Currency> {
        return copy(owner = newOwner, amount = amount.copy(newAmount.quantity))
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MyCashSchemaV1)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is MyCashSchemaV1 -> {
                val issuer: String
                val owner: String
                if (this.issuer is Party) {
                    issuer = (this.issuer as Party).name.toString()
                    owner = (this.owner as Party).name.toString()
                }
                // AnonymousParty
                else {
                    issuer = (this.issuer as AnonymousParty).toString()
                    owner = (this.owner as AnonymousParty).toString()
                }
                MyCashSchemaV1.PersistentMyCash(
                        issuer,
                        owner,
                        this.amount.quantity,
                        this.amount.token.product.currencyCode
                )
            }
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    // Used to compare states with anonymous issuers and owners
    fun compareQuantityAndCurrency(myCash: MyCash): Boolean {
        return (this.amount.quantity == myCash.amount.quantity)
        && (this.amount.token.product.currencyCode == myCash.amount.token.product.currencyCode)
    }
}