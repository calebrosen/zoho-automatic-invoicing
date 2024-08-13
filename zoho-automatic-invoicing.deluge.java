
salesOrderId = salesorder.get("salesorder_id");
organizationID = organization.get("organization_id").toString();
salesorderResponse = invokeurl
[
	url :"https://www.zohoapis.com/inventory/v1/salesorders/" + salesOrderId + "?organization_id=" + organizationID
	type :GET
	connection:"zom"
];
if(salesorderResponse.get("code") != 0)
{
	info "Error fetching sales order. ID: " + salesOrderId + ", Error: " + salesorderResponse.toString();
}
salesorderRes = salesorderResponse.get("salesorder");
soNumber = salesorderRes.get("salesorder_number");
headerMap = Map();
headerMap.put("Accept","application/json");
headerMap.put("Content-Type","application/json");
salesorderID = salesOrderId.toString();
customerID = salesorderRes.get("customer_id").toString();
lineItems = salesorderRes.get("line_items");
accountId = 0;
//getting reporting tags
siteName = lineItems.get(0).get("tags").get(0).get("tag_option_name");
storeForTag = 0;
//dynamically getting store information from Zoho Sheet "Store Tags/ID's/URL's/Keys/Information for Deluge" in Folder "Store Reporting Tags / Account ID's / Etc (For Deluge)" in Zoho Workdrive
getStoreInformation = zoho.sheet.getRecords(~REDACTED~,"StoreInfo",Map());
storeRecords = getStoreInformation.get("records");
for each  storeRecord in storeRecords
{
	tempStore = storeRecord.get("Store");
	if(trim(lower(tempStore)) == trim(lower(siteName)))
	{
		accountId = storeRecord.get("BankAccID");
		storeForTag = storeRecord.get("WebsiteTagOptionID");
	}
}
// end getting reporting tags
//////////////////////////////////////////////////////////////
//start making a custom sales order line item list
lineItemList = List();
shipLineItemList = List();
dropshipLineItemList = List();
shipLineItemIDs = List();
for each  lineItem in lineItems
{
	lineItemMap = Map();
	itemName = lineItem.get("name").toString();
	lineItemMap.put("salesorder_item_id",lineItem.get("line_item_id"));
	lineItemMap.put("item_id",lineItem.get("item_id"));
	lineItemMap.put("name",lineItem.get("name"));
	lineItemMap.put("quantity",lineItem.get("quantity"));
	lineItemMap.put("is_invoiced",lineItem.get("is_invoiced"));
	lineItemMap.put("quantity_invoiced",lineItem.get("quantity_invoiced"));
	lineItemMap.put("discount",lineItem.get("discount"));
	lineItemMap.put("rate",lineItem.get("rate"));
	lineItemMap.put("item_custom_fields",lineItem.get("item_custom_fields"));
	lineItemMap.put("avatax_tax_code",lineItem.get("avatax_tax_code"));
	lineItemMap.put("avatax_tax_code_id",lineItem.get("avatax_tax_code_id"));
	lineItemMap.put("description",lineItem.get("description"));
	lineItemList.add(lineItemMap);
}
// checking for "invoiced" status
if(salesorderRes.get("invoiced_status") != "invoiced")
{
	sal = salesorderResponse.get("salesorder");
	// making an item list for of the packages and adding them to a map
	packages = sal.get("packages");
	packagedLineItemList = List();
	for each  package in packages
	{
		tracking = package.get("shipment_order").get("tracking_number");
		status = package.get("status");
		carrier = package.get("carrier");
		if(status != "not_shipped" && carrier != "Ship Line")
		{
			packageID = package.get("package_id");
			res = invokeurl
			[
				url :"https://www.zohoapis.com/inventory/v1/packages/" + packageID + "?organization_id=" + organizationID
				type :GET
				connection:"zom"
			];
			packageLineItems = res.get("package").get("line_items");
			//info packageLineItems;
			for each  packageLineItem in packageLineItems
			{
				packagedLineItemMap = Map();
				packagedLineItemMap.put("item_id",packageLineItem.get("item_id"));
				packagedLineItemMap.put("salesorder_item_id",packageLineItem.get("so_line_item_id"));
				packagedLineItemMap.put("name",packageLineItem.get("name"));
				packagedLineItemMap.put("quantity",packageLineItem.get("quantity"));
				packagedLineItemMap.put("is_invoiced",packageLineItem.get("is_invoiced"));
				packagedLineItemList.add(packagedLineItemMap);
			}
		}
	}
	salDropShip = sal.get("is_dropshipped");
	if(salDropShip == "true")
	{
		//pulling all associated purchase orders
		purchaseOrders = sal.get("purchaseorders");
		poLineItemList = List();
		for each  purchaseOrder in purchaseOrders
		{
			purchaseOrderID = purchaseOrder.get("purchaseorder_id");
			poRes = invokeurl
			[
				url :"https://www.zohoapis.com/inventory/v1/purchaseorders/" + purchaseOrderID + "?organization_id=" + organizationID
				type :GET
				connection:"zom"
			];
			po = poRes.get("purchaseorder");
			poNumbers = po.get("purchaseorder_number");
			shippingPOCheck = containsIgnoreCase(poNumbers,"-S");
			returnPOCheck = containsIgnoreCase(poNumbers,"-R");
			//only pulling PO's without '-S'
			if(!shippingPOCheck && !returnPOCheck)
			{
				//if tracking is empty, it will not add it to the invoice list
				customFields = po.get("custom_fields");
				trackingNumber = null;
				for each  field in customFields
				{
					if(field.get("api_name") = "cf_tracking_number")
					{
						trackingNumber = field.get("value_formatted");
						break;
					}
				}
				if(trackingNumber != null && trackingNumber != "")
				{
					//adding each line item from every PO to a custom line item list
					poLineItems = po.get("line_items");
					for each  poLineItem in poLineItems
					{
						if(poLineItem.get("name") != "SHIP")
						{
							poLineItemMap = Map();
							poLineItemMap.put("item_id",poLineItem.get("item_id"));
							poLineItemMap.put("quantity",poLineItem.get("quantity"));
							poLineItemMap.put("name",poLineItem.get("name"));
							poLineItemMap.put("salesorder_item_id",poLineItem.get("salesorder_item_id"));
							poLineItemList.add(poLineItemMap);
						}
					}
				}
				// below is the end of pulling only '-S' PO's
			}
			//catching dropshipment PO's (-S) for ship line logic
			else if(shippingPOCheck && !returnPOCheck)
			{
				customFields = po.get("custom_fields");
				trackingNumber = null;
				for each  field in customFields
				{
					if(field.get("api_name") = "cf_tracking_number")
					{
						trackingNumber = field.get("value_formatted");
						break;
					}
				}
				if(trackingNumber != null && trackingNumber != "")
				{
					//adding each ship line item from every -S PO to a custom line item list
					poLineItems = po.get("line_items");
					for each  poLineItem in poLineItems
					{
						if(poLineItem.get("name") = "SHIP")
						{
							poShipLineItemMap = Map();
							poShipLineItemMap.put("item_id",poLineItem.get("item_id"));
							poShipLineItemMap.put("quantity",poLineItem.get("quantity"));
							poShipLineItemMap.put("name",poLineItem.get("name"));
							poShipLineItemMap.put("salesorder_item_id",poLineItem.get("salesorder_item_id"));
							poLineItemList.add(poShipLineItemMap);
						}
					}
				}
			}
			//catching dropshipment PO's (-S) for ship line logic END below
		}
		// below is the end of if dropship = true
	}
	// below is the end of if invoice status != "invoiced"
}
else
{
	info "Sales order " + soNumber + " is already invoiced";
	return;
}
//adding the invoicable items to a cleaned list for invoicing
cleanedLineItemList = List();
for each  item in lineItemList
{
	soItemID = item.get("salesorder_item_id");
	itemChecker = false;
	// checking for salesorder items against packagedLineItemList
	for each  packagedItem in packagedLineItemList
	{
		if(packagedItem.get("salesorder_item_id").toString() == soItemID)
		{
			itemChecker = true;
			break;
		}
	}
	// checking salesorder items against poLineItemList if not found in packagedLineItemList
	if(!itemChecker)
	{
		for each  poItem in poLineItemList
		{
			if(poItem.get("salesorder_item_id").toString() == soItemID)
			{
				itemChecker = true;
				break;
			}
		}
	}
	// this is adding the line item's with no item_id, such as 'CHANGED-3199-47', (things that aren't actually real items), to the invoice
	if(item.get("item_id").isNull() && !item.get("is_invoiced") && item.get("name").isNull())
	{
		cleanedLineItemList.add(item);
	}
	// add to cleanedLineItemList if itemChecker is true (meaning the item was found in either a package or a purchase order) and the item is not already invoiced
	if(itemChecker && item.get("quantity_invoiced") != item.get("quantity"))
	{
		alreadyIn = false;
		for each  cleanedLineItem in cleanedLineItemList
		{
			salesOrderITEMID = cleanedLineItem.get("salesorder_item_id");
			if(salesOrderITEMID == soItemID)
			{
				alreadyIn = true;
				break;
			}
		}
		if(!alreadyIn)
		{
			cleanedLineItemList.add(item);
		}
	}
	// adding the "Shipping Charges" ship line to the invoice. this will only be invoiced if it is being invoiced with other items, and will not create just a ship line invoice
	if(item.get("name") = "SHIP" && !item.get("is_invoiced") && item.get("description") = "Shipping Charges")
	{
		alreadyIn = false;
		for each  cleanedLineItem in cleanedLineItemList
		{
			salesOrderITEMID = cleanedLineItem.get("salesorder_item_id");
			if(salesOrderITEMID == soItemID)
			{
				alreadyIn = true;
				break;
			}
		}
		if(!alreadyIn)
		{
			cleanedLineItemList.add(item);
		}
	}
}
info cleanedLineItemList;
//getting each line item discount total and summing them to get the discount for the invoice
totalDiscount = 0;
itemCount = 0;
hasOnlyShipLineItems = false;
for each  item in cleanedLineItemList
{
	totalDiscount = totalDiscount + item.get("discount");
	//if some of the line item is invoiced (if its more than one), i am doing the math to invoice the correct quantity
	quantity = item.get("quantity");
	quantityInvoiced = item.get("quantity_invoiced");
	if(quantityInvoiced > 0)
	{
		updatedQuantity = quantity - quantityInvoiced;
		item.put("quantity",updatedQuantity);
	}
	//removing these 3 items from the map because they are not needed to invoice and i already did the calculations needed with them
	item.remove("quantity_invoiced");
	item.remove("is_invoiced");
	//adding reporting tags to each line item 
	tags = List();
	tag = Map();
	tag.put("tag_id",2036335000000000333);
	tag.put("tag_option_id",storeForTag);
	tags.add(tag);
	item.put("tags",tags);
	itemCount = itemCount + 1;
	//checking for singular ship line item to stop invoice if it's only the ship line and the sales order has no existing invoices
	itemNameShipCheck = item.get("name");
	if(itemNameShipCheck = "SHIP")
	{
		if(cleanedLineItemList.size() = 1)
		{
			hasShipOnly = true;
		}
	}
	//info itemNameShipCheck;
	//checking for only ship line items for use later
	if(itemNameShipCheck != "SHIP")
	{
		hasOnlyShipLineItems = true;
	}
}
//////////////////////STARTING SHIP LINE CATCHES//////////////////////
if(salesorderRes.get("invoiced_status") = "not_invoiced" && hasShipOnly = true)
{
	info "Not invoicing because it would only be a singular ship line on a sales order that isn't invoiced at all yet.";
	return;
}
if(salesorderRes.get("invoiced_status") = "not_invoiced" && hasOnlyShipLineItems = false)
{
	info "Not invoicing because it would only be ship lines on a sales order that isn't invoiced at all yet.";
	return;
}
//////////////////////ENDING SHIP LINE CATCHES//////////////////////
//ensuring that there are line items to invoice before trying to invoice
if(cleanedLineItemList.size() > 0)
{
	//uncomment these info lines for simple debugging
	//info "Cleaned Line Items: " + cleanedLineItemList;
	//info "Line Items: " + lineItemList;
	//info "Packaged Line Items: " + packagedLineItemList;
	//info "PO Line Items (with tracking): " + poLineItemList;
	invoiceMap = Map();
	invoiceMap.put("line_items",cleanedLineItemList);
	invoiceMap.put("reference_number",soNumber);
	invoiceMap.put("discount",totalDiscount);
	invoiceMap.put("customer_id",salesorderRes.get("customer_id"));
	invoiceMap.put("shipping_charge",salesorderRes.get("shipping_charge"));
	invoiceMap.put("salesperson_id",salesorderRes.get("salesperson_id"));
	if(sal.get("discount_type").toString().equalsIgnoreCase("entity_level"))
	{
		cri = sal.get("is_discount_before_tax");
		invoiceMap.put("is_discount_before_tax",cri);
		invoiceMap.put("discount_type",sal.get("discount_type"));
	}
	if(sal.get("avatax_use_code") == "G")
	{
		useCodeId = sal.get("avatax_use_code_id");
		invoiceMap.put("avatax_use_code_id",useCodeId);
		invoiceMap.put("avatax_use_code",sal.get("avatax_use_code"));
	}
	//////////// ACTUALLY CREATING THE INVOICE /////////////
	response = zoho.inventory.createRecord("Invoices",organizationID,invoiceMap,"zom");
	//any code above 0 is an error code
	if(response.get("code").toString() != "0" && response.get("code").toString() != "36012" && response.get("code").toString() != "1000")
	{
		//sending email to IT to notify an invoice could not be created be done
		sendmail
		[
			from :zoho.loginuserid
			to :~REDACTED~
			subject :"Error automatically creating invoice"
			message :"Error creating invoice for sales order <b>" + soNumber + "</b>.<br>Error message: " + response.get("message") + "<br>Error code: " + response.get("code")
		]
		info "Error sent to IT email";
		//info response;
	}
	else
	{
		//update order status can be implemented here in the future ~updateorderstatus~
		inv = response.get("invoice");
		invoiceID = inv.get("invoice_id");
		//updating the invoice to "sent"
		respond = invokeurl
		[
			url :"https://www.zohoapis.com/inventory/v1/invoices/" + invoiceID + "/status/sent?organization_id=" + organizationID
			type :POST
			connection:"zom"
		];
		customerID = inv.get("customer_id").toString();
		pays = Map();
		amount = inv.get("total");
		taxes = inv.get("tax_amount_withheld");
		invoices = List();
		maper = Map();
		maper.put("invoice_id",invoiceID);
		maper.put("amount_applied",amount);
		maper.put("tax_amount_withheld",taxes);
		invoices.add(maper);
		pays.put("customer_id",customerID);
		pays.put("payment_mode","Credit Card");
		pays.put("reference_number",salesorderRes.get("salesorder_number"));
		pays.put("amount",amount);
		pays.put("invoices",invoices);
		//pays.put("date",invDate);
		pays.put("account_id",accountId);
		jsonstring = Map();
		jsonstring.put("JSONString",pays);
		headersMap1 = Map();
		headersMap1.put("Accept","application/json");
		headersMap1.put("Content-Type","application/json");
		//getting the customers payment and applying it to the invoice
		payment_res = invokeurl
		[
			url :"https://www.zohoapis.com/inventory/v1/customerpayments?organization_id=" + organizationID + "&customer_id=" + customerID
			type :GET
			headers:headerMap
			connection:"zom"
		];
		payments = payment_res.get("customerpayments");
		for each  payment in payments
		{
			payment_ref = payment.get("reference_number");
			if(payment_ref == salesorderRes.get("salesorder_number"))
			{
				paymentID = payment.get("payment_id");
				break;
			}
			else
			{
				info "Something went wrong while retrieving the payment reference number";
			}
		}
		//info "payment_ref: " + payment_ref;
		//info "salesorderRes SONumber: " + salesorderRes.get("salesorder_number");
		//info "payment_id: " + payment.get("payment_id");
		if(paymentID != null)
		{
			//info amount;
			parameters_data = '{"payment_mode": "Credit / Debit Card", "invoices": [{"invoice_id": "' + invoiceID + '", "amount_applied": "' + amount + '"}]}';
			response = invokeurl
			[
				url :"https://www.zohoapis.com/inventory/v1/customerpayments/" + paymentID + "?organization_id=" + organizationID
				type :PUT
				parameters:parameters_data
				headers:headerMap
				connection:"zom"
			];
			if(response.get("code") > 0 && response.get("code") != 1000 && response.get("code") != 24015 && response.get("code") != 110850)
			{
				//sending email to IT on error
				info response;
				sendmail
				[
					from :zoho.loginuserid
					to :~REDACTED~
					subject :"Error applying payment to automatic invoice"
					message :"Could not invoice sales order # " + soNumber + ".<br>Error code: " + response.get("code") + "<br>Error message: " + response.get("message")
				]
			}
		}
		else
		{
			info "Payment could not be found";
		}
		info "The invoice for " + soNumber + " has been created.";
	}
	//end of ensuring that there are line items to invoice before trying to invoice
}
else
{
	info "There are no valid line items to invoice - exiting the function.";
}
