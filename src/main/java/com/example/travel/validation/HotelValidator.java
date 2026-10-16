package com.example.travel.validation;

import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.travel.domain.ValidationException.FieldError;
import tools.jackson.databind.JsonNode;

/**
 * Hotel metadata: property name + address, stay dates, nights (must equal the
 * calendar days between check-in and check-out), room rate.
 */
final class HotelValidator {

    private HotelValidator() {
    }

    static void validate(JsonNode metadata, List<FieldError> errors) {
        MetadataRules.text(metadata, "name", "metadata.name", 100, errors);

        JsonNode address = MetadataRules.object(metadata, "address", "metadata.address", errors);
        if (address != null) {
            MetadataRules.text(address, "street", "metadata.address.street", 200, errors);
            MetadataRules.text(address, "city", "metadata.address.city", 100, errors);
            MetadataRules.text(address, "country", "metadata.address.country", 100, errors);
        }

        var checkIn = MetadataRules.date(metadata, "check_in", "metadata.check_in", errors);
        var checkOut = MetadataRules.date(metadata, "check_out", "metadata.check_out", errors);

        JsonNode nightsNode = metadata.get("nights");
        Integer nights = null;
        if (nightsNode == null || nightsNode.isNull() || !nightsNode.canConvertToInt()) {
            errors.add(new FieldError("metadata.nights", "is required and must be an integer"));
        } else {
            nights = nightsNode.asInt();
            if (nights < 1) {
                errors.add(new FieldError("metadata.nights", "must be >= 1"));
                nights = null;
            }
        }

        if (checkIn != null && checkOut != null) {
            if (!checkOut.isAfter(checkIn)) {
                errors.add(new FieldError("metadata.check_out", "must be after check_in"));
            } else if (nights != null && nights != ChronoUnit.DAYS.between(checkIn, checkOut)) {
                errors.add(new FieldError("metadata.nights", "must equal the number of calendar days between check_in and check_out"));
            }
        }

        MetadataRules.amount(metadata, "room_rate_per_night", "metadata.room_rate_per_night", errors);

        JsonNode extras = metadata.get("extras");
        if (extras != null && !extras.isNull()) {
            if (!extras.isArray()) {
                errors.add(new FieldError("metadata.extras", "must be an array"));
            } else {
                for (int i = 0; i < extras.size(); i++) {
                    JsonNode extra = extras.get(i);
                    String field = "metadata.extras[" + i + "]";
                    if (extra == null || !extra.isObject()) {
                        errors.add(new FieldError(field, "must be an object"));
                        continue;
                    }
                    MetadataRules.text(extra, "name", field + ".name", 100, errors);
                    MetadataRules.amount(extra, "amount", field + ".amount", errors);
                }
            }
        }
    }
}
