### For automated testing; run the below gradle tasks from IntelliJ:
* Run Contract Tests  
* Run Flow Tests

### To run MyCash Cordapp:

```
cd cordapp-mycash 
./gradlew deployNodes
build/nodes/runnodes
```

### Wait until all 4 nodes are ready; then proceed to the below instructions:

#### Inside Bank terminal:
```
# Issue 50 USD to PartyA in 5 installments
flow start IssueFlow$Initiator issuer: "O=Bank,L=New York,C=US", owner: "O=PartyA,L=London,C=GB", amount: 1000, currencyCode: "USD"
flow start IssueFlow$Initiator issuer: "O=Bank,L=New York,C=US", owner: "O=PartyA,L=London,C=GB", amount: 1000, currencyCode: "USD"
flow start IssueFlow$Initiator issuer: "O=Bank,L=New York,C=US", owner: "O=PartyA,L=London,C=GB", amount: 1000, currencyCode: "USD"
flow start IssueFlow$Initiator issuer: "O=Bank,L=New York,C=US", owner: "O=PartyA,L=London,C=GB", amount: 1000, currencyCode: "USD"
flow start IssueFlow$Initiator issuer: "O=Bank,L=New York,C=US", owner: "O=PartyA,L=London,C=GB", amount: 1000, currencyCode: "USD"
```

#### Inside PartyA terminal:
```
# You should see 5 unconsumed MyCash states
run vaultQuery contractStateType: com.template.state.MyCash

# Send 42 USD to PartyB
flow start MoveFlow$Initiator issuer: "O=Bank,L=New York,C=US", owner: "O=PartyA,L=London,C=GB", amount: 4200, currencyCode: "USD", newOwner: "O=PartyB,L=New York,C=US"

# You should see one new MyCash state of value 8 USD
run vaultQuery contractStateType: com.template.state.MyCash

```

#### Inside PartyB terminal:
```
# You should see one new MyCash state of value 42 USD
run vaultQuery contractStateType: com.template.state.MyCash
```

#### Inside PartyA terminal: 
```
# (Copy txHash and txIndex)
run vaultQuery contractStateType: com.template.state.MyCash

# Exit the remaining 8 USD from the ledger
flow start ExitFlow$Initiator txHash: "", txIndex: 

# You shouldn't see any unconsumed states
run vaultQuery contractStateType: com.template.state.MyCash
```

