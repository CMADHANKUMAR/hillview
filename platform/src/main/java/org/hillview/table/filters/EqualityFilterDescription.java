/*
 * Copyright (c) 2017 VMware Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
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

package org.hillview.table.filters;

import org.hillview.table.api.*;

import javax.annotation.Nullable;
import java.util.Objects;

public class EqualityFilterDescription implements ITableFilterDescription {
    public final String column;
    @Nullable
    public final String compareValue;
    public final boolean complement;

    /**
     * Make a filter that accepts rows that (do not) have a specified value in the specified
     * column.
     * @param column Column that is compared.
     * @param compareValue Value that is compared for (in)equality in the column.
     * @param complement If true, invert the filter such that it checks for inequality.
     */
    public EqualityFilterDescription(
            String column, @Nullable String compareValue, boolean complement) {
        this.column = column;
        this.compareValue = compareValue;
        this.complement = complement;
    }

    public EqualityFilterDescription(String column, @Nullable String compareValue) {
        this(column, compareValue, false);
    }

    @Override
    public ITableFilter getFilter(ITable table) {
        return new EqualityFilter(table);
    }

    /**
     * This filter maps a given Table to a Table that only contains the given value in the
     * specified column.
     */
    public class EqualityFilter implements ITableFilter {
        boolean missing;  // if true we look for missing values;
        double  d;
        int     i;
        @Nullable
        String  s;
        private final ColumnAndConverter column;
        private final ContentsKind compareKind;

        public EqualityFilter(ITable table) {
            ColumnAndConverterDescription ccd = new ColumnAndConverterDescription
                    (EqualityFilterDescription.this.column);
            this.column = table.getLoadedColumn(ccd);
            this.compareKind = this.column.column.getKind();

            if (EqualityFilterDescription.this.compareValue == null) {
                this.missing = true;
                return;
            }
            switch (compareKind) {
                case Category:
                case String:
                case Json:
                    this.s = EqualityFilterDescription.this.compareValue;
                    break;
                case Integer:
                    this.i = Integer.parseInt(EqualityFilterDescription.this.compareValue);
                    break;
                case Double:
                case Duration:
                case Date:
                    this.d = Double.parseDouble(EqualityFilterDescription.this.compareValue);
                    break;
                default:
                    throw new RuntimeException("Unexpected kind " + compareKind);
            }
        }

        /**
         * @return Whether the value at the specified row index matches to the compare value.
         */
        @Override
        public boolean test(int rowIndex) {
            boolean result;
            if (column.isMissing(rowIndex)) {
                result = this.missing;
            } else {
                if (this.missing) {
                    result = false;
                } else {
                    switch (compareKind) {
                        case Duration:
                        case Double:
                        case Date:
                            result = column.asDouble(rowIndex) == this.d;
                            break;
                        case Integer:
                            result = column.getInt(rowIndex) == this.i;
                            break;
                        case Category:
                        case String:
                        case Json:
                            result = Objects.equals(column.getString(rowIndex), this.s);
                            break;
                        default:
                            throw new RuntimeException("Unexpected kind " + this.compareKind);
                    }
                }
            }

            return EqualityFilterDescription.this.complement != result;
        }
    }
}
