-- Hernoemt de publicatiedatum-kolom naar publicatietijdstip. De waarde is altijd
-- een date-time (TIMESTAMPTZ) geweest; de oorspronkelijke kolomnaam suggereerde
-- ten onrechte een DATE en is daarom misleidend. V5 voegt de kolom toe en heeft
-- geen indexen/views die de naam expliciet vermelden, dus alleen een RENAME COLUMN
-- volstaat — geen DROP/CREATE van indexen of views nodig.
ALTER TABLE berichten RENAME COLUMN publicatiedatum TO publicatietijdstip;
