//Load SAGE data
//Non-stock items
LOAD CSV WITH HEADERS FROM $sageInventory AS row
WITH row
WHERE 1=1
AND row.`Active?` = "Active"
AND row.`Item Class` = 'Non-stock item'
MERGE (mo:InventoryModel {id: trim(toUpper(row.`Item ID`))})
ON CREATE SET mo:LABOR, mo:Active, mo.created = datetime(), mo.createdBy = "admin", mo.soldByUnit = 'JOB', mo.jobHours = 1, mo.price = 0.0, mo.serialNumRequired = FALSE, mo.sheetCost = 0.0, mo.maxPercentOff = 100.0
SET mo.description = row.`Item Description`
, mo.updated = datetime()
, mo.updatedBy = "admin";

//Stock items
LOAD CSV WITH HEADERS FROM $sageInventory AS row
WITH row
WHERE 1=1
AND row.`Active?` = "Active"
AND row.`Item Class` = 'Stock item'
MERGE (mo:InventoryModel {id: trim(toUpper(row.`Item ID`))})
ON CREATE SET mo:STANDARD, mo:Active, mo.created = datetime(), mo.createdBy = "admin", mo.soldByUnit = 'EACH', mo.price = 0.0, mo.serialNumRequired = FALSE, mo.sheetCost = 0.0, mo.maxPercentOff = 100.0
SET mo.description = row.`Item Description`
, mo.updated = datetime()
, mo.updatedBy = "admin";

CALL apoc.load.json('https://raw.githubusercontent.com/CASA-Technologies/data/refs/heads/main/DiscountWheelAndTire/invoices.json')
YIELD value
WITH value.invoices AS invoices
MATCH (sto:Store {id: 1})
MATCH (sp:Employee {id: 1})
CALL () {
  MATCH (inv:Invoice) 
  RETURN coalesce(max(inv.id), 0) AS maxId
}
UNWIND invoices AS invMap
WITH sto, sp, maxId, invMap
// LIMIT 10
CREATE (inv:Invoice {id: maxId + invMap.invoiceId})
SET inv.createdBy = 'admin', inv.created = datetime()
SET inv.updatedBy = 'admin', inv.updated = datetime()
SET inv.invoiceDate = date(invMap.invoiceDate)
SET inv.posted = true, inv.completed = true, inv.editing = false, inv.fullDay = false, inv.taxExempt = FALSE
SET inv.type = 'STANDARD', inv.paid = invMap.totalAmount, inv.subtotal = invMap.totalAmount, inv.tax = 0
MERGE (sto)-[:CREATED]->(inv)
MERGE (inv)-[:HAS_SALESPERSON_1]->(sp)

MERGE (c:Client:Entity {name: invMap.customer.name})
SET c.lowerCaseName = toLower(invMap.customer.name)
SET c.phone = invMap.customer.phone
SET c.openPaymentTerms = 30, c.taxExempt = FALSE
SET c.created = datetime(), c.updated = datetime()
//SET SEQUENTIAL ID OF ENTITY LATER and taxExempt
MERGE (inv)-[:FOR_CLIENT]->(c)

WITH inv, c, invMap
UNWIND invMap.lines AS lineMap
WITH inv, c, invMap, lineMap
CREATE (il:InvoiceLine {id: randomUUID()})
SET il.miscellaneous = true, il.taxExempt = false
SET il.sortOrder = lineMap.sortOrder
SET il.qty = lineMap.qty
SET il.costPerUnit =  round(coalesce(lineMap.costOfSales, 0)/lineMap.qty, 2)
SET il.price = round(lineMap.amount/lineMap.qty, 2)
SET il.finalPrice = round(lineMap.amount/lineMap.qty, 2)
SET il.model = lineMap.itemId, il.description = lineMap.itemDescription
CREATE (inv)-[:HAS_INV_LINE]->(il)

WITH DISTINCT invMap, inv, c
WHERE invMap.vehicle is not null
CREATE (vi:VehicleInstance {id: randomUUID()})
SET vi.created = datetime()
, vi.year = invMap.vehicle.year
, vi.make = invMap.vehicle.make
, vi.model = invMap.vehicle.model
, vi.vin = invMap.vehicle.vin
, vi.odometer = invMap.vehicle.mileage
, vi.notes = invMap.vehicle.notes
CREATE (inv)-[:FOR_VEHICLE]->(vi)
CREATE (c)-[:HAS_VEHICLE]->(vi)
// RETURN *
;

//History ID setting
MATCH (e:Entity)
WHERE e.id is null
// return count(e)
WITH collect(e) AS entities
WITH entities, size(entities) AS size
CALL () {
  MATCH (e:Entity) 
  RETURN coalesce(max(e.id),0) AS maxId
}
WITH entities, range(maxId + 1, maxId + size) AS ids
WITH entities, apoc.coll.zip(entities, ids) AS zipped
UNWIND zipped AS pair
WITH pair[0] AS node, pair[1] AS id
// RETURN * LIMIT 100
SET node.id = id
;

//OPTIONAL
MATCH (inv:Invoice)-[:FOR_CLIENT]->(c:Client)
WHERE NOT c.taxExempt
SET inv.tax = round(inv.subtotal * .075, 2)
, inv.paid = round(inv.subtotal * 1.075, 2)