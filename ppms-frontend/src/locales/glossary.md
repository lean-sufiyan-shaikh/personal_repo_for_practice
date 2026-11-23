# PPMS translation glossary (English / Hindi / Marathi)

Status: **draft**. Hindi and Marathi were machine-drafted and are waiting for review by native speakers who work on a forecourt. Until then the language picker shows "Translation in review" for both.

Use these words in every `src/locales/hi/*.json` and `src/locales/mr/*.json` file so one thing is never called two names.

## Rules

- **Transliterate** (write the English word in Devanagari) when pump staff already say the English word: nozzle, shift, dip, tank, credit, stock, reading, meter, password. Operators recognise नोज़ल faster than a pure-Hindi coinage.
- **Translate** when an everyday word exists and is what staff say: cash (नकद / रोख), expense (खर्च), salary (वेतन / पगार), total (कुल / एकूण).
- **Keep Latin** for abbreviations, units and codes that are printed on meters, receipts and bills: DU, GST, GSTIN, HSN, CGST, SGST, UPI, CNG, PPMS, ID, L, kg, ml, ₹, A–Z/a–z in the password rule.
- **Digits** are always 0–9 (Latin), never ०–९. The formatters force this; do not type Devanagari digits into translations.
- **Tone**: polite imperative. Hindi uses "करें" forms ("सेव करें"), Marathi uses "करा" forms ("सेव्ह करा"). Address the user as आप / तुम्ही.
- **Placeholders** such as `{{name}}` stay exactly as in English; move them to where the sentence needs them.
- GST tax invoices and credit statements are printed by the backend and stay in English.

## Hindi and Marathi differ

Marathi is not Hindi with different spelling. Watch these:

| Idea | Hindi | Marathi |
|---|---|---|
| cash | नकद | रोख |
| salary / payroll | वेतन | पगार |
| outstanding balance | बकाया | थकबाकी |
| overdue (money) | बकाया | थकीत |
| overdue (time) | समय पार | वेळ उलटली |
| difference / discrepancy | अंतर | फरक |
| short / over | कम / ज़्यादा | कमी / जास्त |
| approved / rejected | मंज़ूर / अस्वीकृत | मंजूर / नाकारले |
| pending | बाकी | प्रलंबित |
| page | पेज | पान |
| documents | दस्तावेज़ | कागदपत्रे |
| price | कीमत | दर |
| amount | राशि | रक्कम |
| interest | ब्याज | व्याज |
| yes / no | हाँ / नहीं | हो / नाही |
| English "æ" sound (tank, manager, balance) | ै (टैंक, मैनेजर, बैलेंस) | ॅ / ॲ (टँक, मॅनेजर, बॅलन्स, ॲडमिन) |
| nukta (z, f) | used: नोज़ल, डीज़ल, फ़ोन | not used: नोझल, डिझेल, फोन |

## Terms

| English | Hindi | Marathi | Decision |
|---|---|---|---|
| Petrol pump | पेट्रोल पंप | पेट्रोल पंप | transliterate |
| Pump | पंप | पंप | transliterate |
| Owner | मालिक | मालक | translate |
| Admin | एडमिन | ॲडमिन | transliterate |
| Manager | मैनेजर | मॅनेजर | transliterate |
| Operator | ऑपरेटर | ऑपरेटर | transliterate |
| Accountant | अकाउंटेंट | अकाउंटंट | transliterate |
| Staff | स्टाफ़ | कर्मचारी | Hindi transliterates; Marathi translates |
| Shift | शिफ्ट | शिफ्ट | transliterate |
| Open shift (status) | चालू | चालू | translate |
| Close shift | शिफ्ट बंद करें | शिफ्ट बंद करा | translate the verb |
| Handover | हैंडओवर | हँडओव्हर | transliterate |
| Shift planning | शिफ्ट योजना | शिफ्ट नियोजन | translate "planning" |
| Day off | छुट्टी | सुट्टी | translate |
| Leave | छुट्टी | रजा | translate |
| Night shift | रात की शिफ्ट | रात्रीची शिफ्ट | translate "night" |
| Nozzle | नोज़ल | नोझल | transliterate |
| DU (dispensing unit) | DU | DU | keep Latin |
| Tank | टैंक | टँक | transliterate |
| Tanker | टैंकर | टँकर | transliterate |
| Delivery | डिलीवरी | डिलिव्हरी | transliterate |
| Dip | डिप | डिप | transliterate |
| Dip check | डिप जाँच | डिप तपासणी | transliterate + translate |
| Meter | मीटर | मीटर | transliterate |
| Reading | रीडिंग | रीडिंग | transliterate |
| Opening reading | शुरुआती रीडिंग | सुरुवातीचे रीडिंग | translate the adjective |
| Closing reading | अंतिम रीडिंग | शेवटचे रीडिंग | translate the adjective |
| Litre | लीटर (unit symbol L) | लिटर (unit symbol L) | transliterate word, keep "L" after numbers |
| Kilogram | kg | kg | keep Latin |
| Petrol | पेट्रोल | पेट्रोल | transliterate |
| Speed Petrol | स्पीड पेट्रोल | स्पीड पेट्रोल | transliterate (brand-like) |
| Diesel | डीज़ल | डिझेल | transliterate |
| Speed Diesel | स्पीड डीज़ल | स्पीड डिझेल | transliterate |
| CNG | CNG | CNG | keep Latin |
| Fuel | ईंधन | इंधन | translate |
| Fuel price | ईंधन कीमत | इंधन दर | translate |
| Rate | दर | दर | translate |
| Stock | स्टॉक | स्टॉक | transliterate |
| Inventory | स्टॉक | स्टॉक | use "stock"; staff do not say "inventory" |
| Lot | लॉट | लॉट | transliterate |
| Calibration | कैलिब्रेशन | कॅलिब्रेशन | transliterate |
| Sale | बिक्री | विक्री | translate |
| Shop sale (counter) | दुकान बिक्री | दुकान विक्री | translate |
| Products (ancillary) | उत्पाद | उत्पादने | translate |
| Cash | नकद | रोख | translate |
| Cash drawer | कैश ड्रॉअर | कॅश ड्रॉवर | transliterate (the object) |
| UPI | UPI | UPI | keep Latin |
| Card / Fleet card | कार्ड / फ़्लीट कार्ड | कार्ड / फ्लीट कार्ड | transliterate |
| Bank transfer | बैंक ट्रांसफ़र | बँक ट्रान्सफर | transliterate |
| Credit | क्रेडिट | क्रेडिट | transliterate (not उधार: credit here is a formal account) |
| Credit client / customer | क्रेडिट ग्राहक | क्रेडिट ग्राहक | translate "customer" |
| Credit limit | क्रेडिट सीमा | क्रेडिट मर्यादा | translate "limit" |
| Credit extension | क्रेडिट विस्तार | क्रेडिट वाढ | translate "extension" |
| Outstanding | बकाया | थकबाकी | translate |
| Overdue | बकाया / समय पार | थकीत / वेळ उलटली | money vs time, see above |
| Billing cycle | बिलिंग चक्र | बिलिंग चक्र | transliterate + translate |
| Payment | भुगतान | पेमेंट | Hindi translates; Marathi transliterates |
| Interest | ब्याज | व्याज | translate |
| Invoice | इनवॉइस | इनव्हॉइस | transliterate (invoice print stays English) |
| Statement | स्टेटमेंट | स्टेटमेंट | transliterate (statement print stays English) |
| GST / GSTIN / HSN | GST / GSTIN / HSN | GST / GSTIN / HSN | keep Latin |
| Settlement | सेटलमेंट | सेटलमेंट | transliterate |
| Expense | खर्च | खर्च | translate |
| Salary / Payroll | वेतन | पगार | translate |
| Salary deduction | वेतन से कटौती | पगारातून कपात | translate |
| Discrepancy | अंतर | फरक | translate |
| Short (cash) | कम | कमी | translate |
| Over (cash) | ज़्यादा | जास्त | translate |
| Balanced | संतुलित | जुळले | translate |
| Approval | मंज़ूरी | मंजुरी | translate |
| Approved | मंज़ूर | मंजूर | translate |
| Rejected | अस्वीकृत | नाकारले | translate |
| Pending approval | मंज़ूरी बाकी | मंजुरी प्रलंबित | translate |
| Draft | ड्राफ्ट | मसुदा | Hindi transliterates; Marathi translates |
| Active / Inactive | सक्रिय / निष्क्रिय | सक्रिय / निष्क्रिय | translate |
| Suspended | निलंबित | निलंबित | translate |
| Balance sheet | बैलेंस शीट | बॅलन्स शीट | transliterate |
| Report | रिपोर्ट | रिपोर्ट | transliterate (staff say रिपोर्ट, not अहवाल) |
| Audit log | ऑडिट लॉग | ऑडिट लॉग | transliterate |
| Overview / Dashboard home | सारांश / होम | सारांश / होम | translate "overview"; "Home" tab transliterated |
| Setup | सेटअप | सेटअप | transliterate |
| Notification | सूचना | सूचना | translate |
| Document | दस्तावेज़ | कागदपत्र | translate |
| Password | पासवर्ड | पासवर्ड | transliterate |
| Temporary password | अस्थायी पासवर्ड | तात्पुरता पासवर्ड | translate the adjective |
| Phone number | फ़ोन नंबर | फोन नंबर | transliterate |
| Sign in / Sign out | साइन इन / साइन आउट | साइन इन / साइन आउट | transliterate |
| Log out | लॉग आउट | लॉग आउट | transliterate |
| Language | भाषा | भाषा | translate |
| Save | सेव करें | सेव्ह करा | transliterate the verb |
| Cancel | रद्द करें | रद्द करा | translate |
| Delete | हटाएँ | हटवा | translate |
| Close (dialog) | बंद करें | बंद करा | translate |
| Search | खोजें | शोधा | translate |
| Select | चुनें | निवडा | translate |
| Total | कुल | एकूण | translate |
| Amount | राशि | रक्कम | translate |
| Date | तारीख | तारीख | translate |
| Today / Yesterday | आज / कल | आज / काल | translate |
| Financial year (FY) | वित्त वर्ष | आर्थिक वर्ष | translate |
