#!/usr/bin/env bash
set -euo pipefail
BS="${BOOTSTRAP:-kafka:9092}"
T=/opt/kafka/bin/kafka-topics.sh
mk() { "$T" --bootstrap-server "$BS" --create --if-not-exists --topic "$1" --partitions "$2" --replication-factor 1 --config retention.ms=86400000; }
mk seat.commands 12;    mk seat.events 12
mk order.events 6;      mk payment.commands 6;  mk payment.events 6
mk ticket.events 3;     mk sale.events 1;       mk demo.control 1
mk ui.events 12
for t in seat.commands seat.events payment.commands payment.events order.events ticket.events sale.events demo.control; do mk "$t.dlq" 1; done
"$T" --bootstrap-server "$BS" --list
