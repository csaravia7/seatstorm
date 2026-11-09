#!/usr/bin/env bash
# Run after load tests, with the system at rest (consumer lag 0).
set -euo pipefail
Q() { docker compose exec -T postgres psql -U seatstorm -d "$1" -Atc "$2"; }
sold=$(Q inventory_db "select count(*) from seat where state='SOLD'")
held_no_order=$(Q inventory_db "select count(*) from seat where state in ('HELD','SOLD') and order_id is null")
total=$(Q inventory_db "select count(*) from seat")
confirmed_seats=$(Q order_db "select coalesce(sum(cardinality(seat_ids)),0) from orders where status='CONFIRMED'")
tickets=$(Q ticket_db "select count(*) from ticket")
confirmed_orders=$(Q order_db "select count(*) from orders where status='CONFIRMED'")
echo "sold=$sold confirmed_seats=$confirmed_seats total=$total tickets=$tickets confirmed_orders=$confirmed_orders"
[ "$held_no_order" -eq 0 ]            || { echo "FAIL: seats without order"; exit 1; }
[ "$sold" -le "$total" ]              || { echo "FAIL: oversold"; exit 1; }
[ "$sold" -eq "$confirmed_seats" ]    || { echo "FAIL: sold seats != confirmed order seats"; exit 1; }
[ "$tickets" -eq "$confirmed_orders" ]|| { echo "FAIL: tickets != confirmed orders"; exit 1; }
echo OK
