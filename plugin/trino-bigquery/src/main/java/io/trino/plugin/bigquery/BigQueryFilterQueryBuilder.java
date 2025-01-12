/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.bigquery;

import com.google.common.collect.ImmutableList;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class BigQueryFilterQueryBuilder
{
    private static final String QUOTE = "`";
    private static final String ESCAPED_QUOTE = "``";
    private final TupleDomain<ColumnHandle> tupleDomain;

    public static Optional<String> buildFilter(TupleDomain<ColumnHandle> tupleDomain)
    {
        return new BigQueryFilterQueryBuilder(tupleDomain).buildFilter();
    }

    private BigQueryFilterQueryBuilder(TupleDomain<ColumnHandle> tupleDomain)
    {
        this.tupleDomain = tupleDomain;
    }

    private Optional<String> buildFilter()
    {
        Optional<Map<ColumnHandle, Domain>> domains = tupleDomain.getDomains();
        return domains.map(this::toConjuncts)
                .map(this::concat);
    }

    private String concat(List<String> clauses)
    {
        return clauses.isEmpty() ? null : clauses.stream().collect(joining(" AND "));
    }

    private List<String> toConjuncts(Map<ColumnHandle, Domain> domains)
    {
        List<BigQueryColumnHandle> columns = domains.keySet().stream().map(BigQueryColumnHandle.class::cast).collect(toList());
        return toConjuncts(columns);
    }

    private List<String> toConjuncts(List<BigQueryColumnHandle> columns)
    {
        if (tupleDomain.isNone()) {
            return ImmutableList.of("FALSE");
        }
        ImmutableList.Builder<String> clauses = ImmutableList.builder();
        for (BigQueryColumnHandle column : columns) {
            Domain domain = tupleDomain.getDomains().get().get(column);
            if (domain != null) {
                clauses.add(toPredicate(column.getName(), domain, column));
            }
        }
        return clauses.build();
    }

    private String toPredicate(String columnName, Domain domain, BigQueryColumnHandle column)
    {
        if (domain.getValues().isNone()) {
            return domain.isNullAllowed() ? quote(columnName) + " IS NULL" : "FALSE";
        }

        if (domain.getValues().isAll()) {
            return domain.isNullAllowed() ? "TRUE" : quote(columnName) + " IS NOT NULL";
        }

        List<String> disjuncts = new ArrayList<>();
        List<Object> singleValues = new ArrayList<>();
        for (Range range : domain.getValues().getRanges().getOrderedRanges()) {
            checkState(!range.isAll()); // Already checked
            if (range.isSingleValue()) {
                singleValues.add(range.getSingleValue());
            }
            else {
                List<String> rangeConjuncts = new ArrayList<>();
                if (!range.isLowUnbounded()) {
                    rangeConjuncts.add(toPredicate(columnName, range.isLowInclusive() ? ">=" : ">", range.getLowBoundedValue(), column));
                }
                if (!range.isHighUnbounded()) {
                    rangeConjuncts.add(toPredicate(columnName, range.isHighInclusive() ? "<=" : "<", range.getHighBoundedValue(), column));
                }
                // If rangeConjuncts is null, then the range was ALL, which should already have been checked for
                checkState(!rangeConjuncts.isEmpty());
                disjuncts.add("(" + concat(rangeConjuncts) + ")");
            }
        }

        // Add back all of the possible single values either as an equality or an IN predicate
        if (singleValues.size() == 1) {
            disjuncts.add(toPredicate(columnName, "=", getOnlyElement(singleValues), column));
        }
        else if (singleValues.size() > 1) {
            String values = singleValues.stream()
                    .map(value -> column.getBigQueryType().convertToString(column.getTrinoType(), value))
                    .collect(joining(","));
            disjuncts.add(quote(columnName) + " IN (" + values + ")");
        }

        // Add nullability disjuncts
        checkState(!disjuncts.isEmpty());
        if (domain.isNullAllowed()) {
            disjuncts.add(quote(columnName) + " IS NULL");
        }

        return "(" + String.join(" OR ", disjuncts) + ")";
    }

    private String toPredicate(String columnName, String operator, Object value, BigQueryColumnHandle column)
    {
        String valueAsString = column.getBigQueryType().convertToString(column.getTrinoType(), value);
        return quote(columnName) + " " + operator + " " + valueAsString;
    }

    private String quote(String name)
    {
        return QUOTE + name.replace(QUOTE, ESCAPED_QUOTE) + QUOTE;
    }
}
