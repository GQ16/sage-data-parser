import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Convert DiscountWheelAndTire/Sales.csv (under src/main/resources) into a JSON structure:
 * {
 *   "invoices": [ Invoice, ... ]
 * }
 *
 * CSV is loaded from the classpath (resources), not from a file-system path.
 *
 * Usage (from a Maven project):
 *   mvn exec:java -Dexec.mainClass="your.package.SalesToInvoiceJson" -Dexec.args="output.json"
 *
 * The code expects the CSV to be at: /DiscountWheelAndTire/Sales.csv on the classpath
 * (i.e., src/main/resources/DiscountWheelAndTire/Sales.csv).
 */
public class SalesToInvoiceJson {

    // --- Data model classes ---

    public static class Root {
        public List<Invoice> invoices = new ArrayList<>();
    }

    public static class Invoice {
        public int invoiceId;
        public LocalDate invoiceDate;
        public String notes;
        public BigDecimal totalQty;
        public BigDecimal totalAmount;
        public BigDecimal totalCostOfSales;
        public BigDecimal totalGrossProfit;
        public BigDecimal totalGrossMarginPercent;
        public String sourceCustomerId;
        public Customer customer;
        public Vehicle vehicle;
        public List<InvoiceLine> lines = new ArrayList<>();
    }

    public static class Customer {
        public String customerId;
        public String name;
        public String billToContact;
        public String phone;
    }

    public static class Vehicle {
        public int year;
        public String make;
        public String model;
        public String description;
        public String vin;
        public int mileage;
    }

    public static class InvoiceLine {
    	public int sortOrder;
        public String itemId;
        public String itemDescription;
        public String stockingUm;
        public BigDecimal qty;
        public BigDecimal amount;
        public BigDecimal costOfSales;
        public BigDecimal grossProfit;
        public BigDecimal grossMarginPercent;
        public String serialNumber;
        public String serialNumberStatus;
        public String itemType;
        public String itemCategory;
        public boolean isCommentOnly;
    }

    // --- Column indices (see previous explanation) ---

    private static final int COL_CUSTOMER_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_ITEM_ID = 2;
    private static final int COL_BILL_TO_CONTACT = 3;
    private static final int COL_LAST_INV_DATE = 4;
    private static final int COL_PHONE = 5;
    private static final int COL_QTY = 6;
    private static final int COL_STOCKING_UM = 7;
    private static final int COL_AMOUNT = 8;
    private static final int COL_COST_OF_SALES = 9;
    private static final int COL_GROSS_PROFIT = 10;
    private static final int COL_GROSS_MARGIN = 11;
    private static final int COL_ITEM_DESC = 12;
    private static final int COL_SERIAL_NUMBER = 13;
    private static final int COL_SERIAL_STATUS = 14;
    private static final int COL_ITEM_TYPE = 16;
    private static final int COL_ITEM_CATEGORY = 18;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("M/d/yy");
	private static int startId = 0;

    /**
     * args[0] = output JSON path (relative or absolute).
     * CSV is always read from classpath: /DiscountWheelAndTire/Sales.csv
     */
    public static void main(String[] args) throws IOException, CsvException {
        if (args.length < 1) {
            System.err.println("Usage: java SalesToInvoiceJson <outputJsonPath>");
            System.exit(1);
        }

        String outputJson = args[0];

        // Load CSV from resources on the classpath
        String resourcePath = "/Sales.csv";
        InputStream csvStream = SalesToInvoiceJson.class.getResourceAsStream(resourcePath);
        if (csvStream == null) {
            throw new IllegalStateException("Could not find CSV on classpath at: " + resourcePath);
        }

        Root root = convertCsvToInvoices(csvStream);

        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .findAndRegisterModules(); // registers JavaTimeModule
        
        //so that dates don't get serialized in the json as Lists [2026, 1, 2] and instead get serialized according to java.toString() conventions
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false); 
        
        mapper.writeValue(Paths.get(outputJson).toFile(), root);
    }

    public static Root convertCsvToInvoices(InputStream csvStream) throws IOException, CsvException {
        Root root = new Root();

        try (CSVReader reader = new CSVReader(new InputStreamReader(csvStream))) {
            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                return root;
            }

            int startIdx = 1; // skip header

            Invoice currentInvoice = null;
            int invoiceSeq = 1;

            for (int i = startIdx; i < rows.size(); i++) {
                String[] row = rows.get(i);

                // Skip blank rows entirely
                if (isRowEmpty(row)) {
                    continue;
                }

                if (isSummaryRow(row)) {
                    if (currentInvoice != null) {
                        applySummaryToInvoice(currentInvoice, row);
                        if (!hasMinimalVehicle(currentInvoice.vehicle)) {
                            currentInvoice.vehicle = null;
                        }
                        root.invoices.add(currentInvoice);
                        currentInvoice = null;
                    }
                    continue;
                }

                // Grab customer + date from this row
                String rowCustomerId = safeGet(row, COL_CUSTOMER_ID);
                String rowCustomerName = safeGet(row, COL_NAME);
                LocalDate rowDate = parseDate(safeGet(row, COL_LAST_INV_DATE));

                boolean hasCustomerOnRow = !isBlank(rowCustomerId) || !isBlank(rowCustomerName);

                if (currentInvoice == null) {
                    // We are not currently in an invoice: only start if we see a customer
                    if (hasCustomerOnRow) {
                        currentInvoice = new Invoice();
                        currentInvoice.invoiceId = startId  + (invoiceSeq++);
                        currentInvoice.customer = buildCustomerFromRow(row);
                        currentInvoice.invoiceDate = rowDate;
                        currentInvoice.sourceCustomerId = rowCustomerId;
                        // Continue to classify this same row as vehicle/line below
                    } else {
                        // No active invoice and no customer on the row: ignore (defensive)
                        continue;
                    }
                } else {
                    // We already have an open invoice: decide whether this row belongs to it
                    String curCustId = currentInvoice.customer.customerId == null
                            ? "" : currentInvoice.customer.customerId;
                    String curCustName = currentInvoice.customer.name == null
                            ? "" : currentInvoice.customer.name;

                    boolean sameCustomer =
                            curCustId.equalsIgnoreCase(rowCustomerId) &&
                            curCustName.equalsIgnoreCase(rowCustomerName);

                    boolean sameDate =
                            (currentInvoice.invoiceDate == null && rowDate == null) ||
                            (currentInvoice.invoiceDate != null && currentInvoice.invoiceDate.equals(rowDate));

                    if (hasCustomerOnRow && !(sameCustomer && sameDate)) {
                        // New customer / new invoice date encountered -> close previous invoice, start new
                        root.invoices.add(currentInvoice);

                        currentInvoice = new Invoice();
                        currentInvoice.invoiceId = startId + (invoiceSeq++);
                        currentInvoice.customer = buildCustomerFromRow(row);
                        currentInvoice.invoiceDate = rowDate;
                        currentInvoice.sourceCustomerId = rowCustomerId;
                        // Then continue to classify this row as vehicle or line
                    } else if (hasCustomerOnRow && sameCustomer && currentInvoice.invoiceDate == null && rowDate != null) {
                        // If current invoice had no date yet and this row does, fill it in
                        currentInvoice.invoiceDate = rowDate;
                    }
                    // If row has no customer but we are in an invoice, we just treat it as part of this invoice.
                }

                // At this point, currentInvoice is guaranteed non-null and this row belongs to it.

                if (isVehicleCommentRow(row)) {
                    Vehicle v = buildOrUpdateVehicle(currentInvoice.vehicle, row);
                    currentInvoice.vehicle = v;
                } else if (isLineItemRow(row)) {
                    InvoiceLine line = buildLineFromRow(row);
                    line.sortOrder = currentInvoice.lines.size();
                    currentInvoice.lines.add(line);
                } else {
                    // Non-financial, non-vehicle descriptive row -> invoice-level note
                    String desc = safeGet(row, COL_ITEM_DESC);
                    if (!isBlank(desc)) {
                        if (currentInvoice.notes == null) {
                            currentInvoice.notes = "";
                        }
                        currentInvoice.notes += desc.trim() + '\n';
                    }
                }
            }

            // If file ends without a summary row, still emit the open invoice
            if (currentInvoice != null) {
                if (!hasMinimalVehicle(currentInvoice.vehicle)) {
                    currentInvoice.vehicle = null;
                }
                root.invoices.add(currentInvoice);
            }
        }

        return root;
    }

    // --- Row classification helpers ---

    private static boolean isRowEmpty(String[] row) {
        for (String s : row) {
            if (!isBlank(s)) return false;
        }
        return true;
    }

    private static boolean isSummaryRow(String[] row) {
        String custId = safeGet(row, COL_CUSTOMER_ID);
        String name = safeGet(row, COL_NAME);
        String itemId = safeGet(row, COL_ITEM_ID);

        if (!isBlank(custId) || !isBlank(name) || !isBlank(itemId)) {
            return false;
        }

        String qty = safeGet(row, COL_QTY);
        String amount = safeGet(row, COL_AMOUNT);
        String cost = safeGet(row, COL_COST_OF_SALES);
//        String gp = safeGet(row, COL_GROSS_PROFIT);
//        String gm = safeGet(row, COL_GROSS_MARGIN);

        boolean hasSomeNumeric = isNumeric(qty) || isNumeric(amount) || isNumeric(cost);
        boolean noDescription = isBlank(safeGet(row, COL_ITEM_DESC));

        return hasSomeNumeric && noDescription;
    }

    private static boolean isVehicleCommentRow(String[] row) {
        String desc = safeGet(row, COL_ITEM_DESC);
        if (isBlank(desc)) return false;

        String itemId = safeGet(row, COL_ITEM_ID);
        if (!isBlank(itemId)) return false;

        String qty = safeGet(row, COL_QTY);
        String amount = safeGet(row, COL_AMOUNT);
        String cost = safeGet(row, COL_COST_OF_SALES);
        String gp = safeGet(row, COL_GROSS_PROFIT);
        String gm = safeGet(row, COL_GROSS_MARGIN);

        boolean hasFinancials =
                isNumeric(qty) || isNumeric(amount) || isNumeric(cost) ||
                isNumeric(gp) || isNumeric(gm);

        if (hasFinancials) return false;

        String descUpper = desc.toUpperCase(Locale.ROOT);
        return descUpper.contains("MILEAGE") ||
               descUpper.startsWith("VIN") ||
               descUpper.contains("VIN:") ||
               descUpper.matches(".*\\b(TOYOTA|HONDA|NISSAN|FORD|CHEVY|CHEVROLET|GMC|JEEP|DODGE|LEXUS|KIA|HYUNDAI|SUBARU|MERCEDES|BUICK|MAZDA)\\b.*");
    }

    private static boolean isLineItemRow(String[] row) {
        String itemId = safeGet(row, COL_ITEM_ID);
        String qty = safeGet(row, COL_QTY);
        String amount = safeGet(row, COL_AMOUNT);

        if (!isBlank(itemId)) return true;
        return isNumeric(qty) || isNumeric(amount);
    }

    // --- Builders ---

    private static Customer buildCustomerFromRow(String[] row) {
        Customer c = new Customer();
        c.customerId = safeGet(row, COL_CUSTOMER_ID);
        c.name = safeGet(row, COL_NAME);
        c.billToContact = safeGet(row, COL_BILL_TO_CONTACT);
        c.phone = normalizePhone(safeGet(row, COL_PHONE));
        return c;
    }
    
    private static String normalizePhone(String raw) {
        if (raw == null) return null;

        // Remove everything except digits
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        // Strip leading '1' if we have 11 digits and it starts with 1 (i.e., +1 country code)
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }

        // If it's not 10 digits after normalization, keep the digits anyway
        // (or return null if you prefer to drop invalids)
        return digits;
    }

    private static Vehicle buildOrUpdateVehicle(Vehicle existing, String[] row) {
        String desc = safeGet(row, COL_ITEM_DESC);
        if (isBlank(desc)) {
            return existing;
        }

        String upper = desc.toUpperCase(Locale.ROOT);

        boolean looksLikeVin = upper.startsWith("VIN") || upper.contains("VIN:") || upper.startsWith("VIN#");
        boolean looksLikeMileage = upper.contains("MILEAGE") || upper.contains("MILES:");

        // If it doesn't look like VIN or MILEAGE, do not change vehicle here.
        // Invoice-level notes will have captured/retain this description already.
        if (!looksLikeVin && !looksLikeMileage) {
            return existing;
        }

        if (existing == null) {
            existing = new Vehicle();
        }

        if (looksLikeVin) {
            // Normalize VIN: strip VIN/VIN:/VIN# prefixes and whitespace/colons
            String vin = desc
                    .replace("VIN#", "")
                    .replace("VIN:", "")
                    .replace("VIN", "")
                    .replace("vin#", "")
                    .replace("vin:", "")
                    .replace("vin", "")
                    .replace(":", " ")
                    .trim();
            vin = vin.replaceAll("\\s+", ""); // collapse spaces

            if (!isBlank(vin)) {
                if (existing.vin == null || existing.vin.isEmpty()) {
                    existing.vin = vin;
                } else if (!vin.equalsIgnoreCase(existing.vin)) {
                    // We won't store conflicting VINs in vehicle; they can be tracked via invoiceNotes if needed.
                }
            }
            return existing;
        }

        if (looksLikeMileage) {
            // Examples from your CSV:
            // "2015 MAZDA 3 MILES: 117,308"
            // "2016 Ford Fusion MILEAGE 174,895"
            // "2018 GMC SIERRA 1500 MILES:" (no mileage number)
            String text = desc.trim();

            // First, find the mileage segment
            int mileIdx = upper.indexOf("MILE");
            int cutIdx = (mileIdx >= 0) ? mileIdx : text.length();
            String left = text.substring(0, cutIdx).trim();   // "2015 MAZDA 3"
            String right = text.substring(cutIdx).trim();     // "MILES: 117,308" or "MILES:" etc.

            // Parse year/make/model from left part
            String[] tokens = left.split("\\s+");
            int year = 0;
            int yearIndex = -1;
            for (int i = 0; i < tokens.length; i++) {
                String digitOnly = tokens[i].replaceAll("[^0-9]", "");
                if (digitOnly.length() == 4 && digitOnly.matches("\\d{4}")) {
                    year = Integer.parseInt(digitOnly);
                    yearIndex = i;
                    break;
                }
            }

            String make = null;
            String model = null;
            if (yearIndex != -1 && yearIndex + 1 < tokens.length) {
                StringBuilder mm = new StringBuilder();
                for (int j = yearIndex + 1; j < tokens.length; j++) {
                    if (mm.length() > 0) mm.append(' ');
                    mm.append(tokens[j].replaceAll("[,]", ""));
                }
                String mmStr = mm.toString().trim();
                if (!mmStr.isEmpty()) {
                    String[] mmParts = mmStr.split("\\s+", 2);
                    make = mmParts[0];
                    if (mmParts.length > 1) {
                        model = mmParts[1];
                    }
                }
            }

            // Only set year/make/model if we actually parsed them
            if (year != 0) existing.year = year;
            if (make != null) existing.make = make;
            if (model != null) existing.model = model;

            // Parse mileage from right side, if any digits exist
            String digitsOnly = right.replaceAll("[^0-9]", "");
            if (!digitsOnly.isEmpty()) {
                try {
                    existing.mileage = Integer.parseInt(digitsOnly);
                } catch (NumberFormatException ignored) {
                    // leave mileage as default 0
                }
            }

            // Optionally keep the full descriptive line if it actually looks like a proper vehicle summary
            if (existing.description == null && year != 0 && make != null) {
                existing.description = text;
            }

            return existing;
        }

        return existing;
    }

    private static InvoiceLine buildLineFromRow(String[] row) {
        InvoiceLine line = new InvoiceLine();

        line.itemId = safeGet(row, COL_ITEM_ID);
        line.itemDescription = safeGet(row, COL_ITEM_DESC);
        line.stockingUm = safeGet(row, COL_STOCKING_UM);
        line.qty = parseBigDecimal(safeGet(row, COL_QTY));
        line.amount = parseBigDecimal(safeGet(row, COL_AMOUNT));
        line.costOfSales = parseBigDecimal(safeGet(row, COL_COST_OF_SALES));
        line.grossProfit = parseBigDecimal(safeGet(row, COL_GROSS_PROFIT));
        line.grossMarginPercent = parseBigDecimal(safeGet(row, COL_GROSS_MARGIN));
        line.serialNumber = safeGet(row, COL_SERIAL_NUMBER);
        line.serialNumberStatus = safeGet(row, COL_SERIAL_STATUS);

        line.itemType = safeGet(row, COL_ITEM_TYPE);
        line.itemCategory = safeGet(row, COL_ITEM_CATEGORY);

        line.isCommentOnly =
                (line.qty == null || BigDecimal.ZERO.compareTo(line.qty) == 0) &&
                line.amount == null &&
                line.costOfSales == null &&
                !isBlank(line.itemDescription);

        return line;
    }

    private static void applySummaryToInvoice(Invoice invoice, String[] row) {
        invoice.totalQty = parseBigDecimal(safeGet(row, COL_QTY));
        invoice.totalAmount = parseBigDecimal(safeGet(row, COL_AMOUNT));
        invoice.totalCostOfSales = parseBigDecimal(safeGet(row, COL_COST_OF_SALES));
        invoice.totalGrossProfit = parseBigDecimal(safeGet(row, COL_GROSS_PROFIT));
        invoice.totalGrossMarginPercent = parseBigDecimal(safeGet(row, COL_GROSS_MARGIN));
    }

    // --- Utility methods ---

    private static String safeGet(String[] row, int idx) {
        if (row == null || idx < 0 || idx >= row.length) return "";
        String v = row[idx];
        return v == null ? "" : v.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNumeric(String s) {
        if (isBlank(s)) return false;
        try {
            new BigDecimal(s.replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BigDecimal parseBigDecimal(String s) {
        if (isBlank(s)) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (isBlank(s)) return null;
        try {
            return LocalDate.parse(s.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static boolean hasMinimalVehicle(Vehicle v) {
        if (v == null) return false;
        // require at least year + make + model OR a VIN
        boolean hasYMM = (v.year != 0 && v.make != null && !v.make.isEmpty()
                && v.model != null && !v.model.isEmpty());
        return hasYMM;
    }
}