/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.carddemo.util;

import com.carddemo.domain.TransactionCategoryBalance;
import java.util.Objects;

/**
 * The one authority for the transaction-category-balance composite key as a concatenated key image.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The {@code TCATBALF} cluster is keyed on three fields, and its cluster definition in
 * {@code app/jcl/TCATBALF.jcl} declares that key as a single 17-byte run at offset 0 of the record -
 * account identifier 11, type code 2, category code 4, in that order and contiguous, as
 * {@link TranCatBalRecordMapper} publishes them. Any keyset cursor over the cluster therefore has to
 * carry the whole 17-byte image as one value and split it back into three parts to resume.
 *
 * <p>Four call sites needed exactly that, and each had grown its own private pair of helpers: one to
 * concatenate the three parts of a row into a cursor value, one to slice a cursor value back into
 * parts. Four copies of a key layout is four chances for one of them to slice at a different offset,
 * and nothing would have failed to compile - the cursor would simply have resumed in the wrong place
 * and skipped or repeated rows. That is the whole reason this class is not "a small helper worth
 * inlining": it is a record layout, and the module keeps every record layout in exactly one place.
 *
 * <h2>What the parts mean, and why a short image is legal</h2>
 *
 * <p>A keyset cursor starts from a value <em>below</em> every stored key, and the natural such value is
 * the empty string. A shorter-than-declared image is therefore an ordinary, expected input and not an
 * error: it means "the parts this image does not reach are unconstrained", which is precisely what an
 * initial cursor position needs. Each accessor returns the empty string for a part the image does not
 * reach, rather than padding, because padding would invent a lower bound the caller did not state.
 *
 * <p>The category part is deliberately <strong>not</strong> truncated to its declared width. A stored
 * category code is a zoned-decimal field whose final byte may carry an overpunched sign, and the
 * repository's after-key predicate compares the three parts it is given; taking whatever the image
 * holds beyond the type code keeps a caller free to hand back a value the store produced without this
 * class deciding it was too long.
 *
 * <h2>Provenance</h2>
 *
 * <p>Field order and widths are read from {@code app/cpy/CVTRA01Y.cpy} through
 * {@link TranCatBalRecordMapper}, at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text
 * is copied here; widths and offsets are metadata describing where the mapping came from.
 *
 * @see TranCatBalRecordMapper
 * @since 1.0.0
 */
public final class TransactionCategoryBalanceKeyCodec {

    /** Zero-based offset of the account-identifier part within the key image. */
    public static final int ACCOUNT_ID_OFFSET = 0;

    /** Width of the account-identifier part. */
    public static final int ACCOUNT_ID_LENGTH = TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH;

    /** Zero-based offset of the type-code part within the key image. */
    public static final int TYPE_CODE_OFFSET = ACCOUNT_ID_OFFSET + ACCOUNT_ID_LENGTH;

    /** Width of the type-code part. */
    public static final int TYPE_CODE_LENGTH = TranCatBalRecordMapper.TRANCAT_TYPE_CD_LENGTH;

    /** Zero-based offset of the category-code part within the key image. */
    public static final int CATEGORY_CODE_OFFSET = TYPE_CODE_OFFSET + TYPE_CODE_LENGTH;

    /** Width of the category-code part. */
    public static final int CATEGORY_CODE_LENGTH = TranCatBalRecordMapper.TRANCAT_CD_LENGTH;

    /** Width of the whole concatenated key image, which is the cluster's declared key length. */
    public static final int KEY_LENGTH =
            TranCatBalRecordMapper.ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH;

    /** The cursor value that lies below every stored key, from which a forward scan starts. */
    public static final String LOW_VALUES = "";

    /** Empty part, returned for a part the supplied image does not reach. */
    private static final String UNCONSTRAINED = "";

    /**
     * Verifies once, at class initialisation, that the published geometry is the contiguous run the
     * cluster definition declares, so a mis-typed figure fails on first use rather than resuming a
     * cursor one byte out.
     */
    static {
        if (TYPE_CODE_OFFSET != ACCOUNT_ID_LENGTH
                || CATEGORY_CODE_OFFSET != ACCOUNT_ID_LENGTH + TYPE_CODE_LENGTH
                || KEY_LENGTH != ACCOUNT_ID_LENGTH + TYPE_CODE_LENGTH + CATEGORY_CODE_LENGTH) {
            throw new IllegalStateException("the transaction-category-balance key parts must form one"
                    + " contiguous run from zero summing to the declared key length of " + KEY_LENGTH);
        }
    }

    private TransactionCategoryBalanceKeyCodec() {
        throw new AssertionError(
                "TransactionCategoryBalanceKeyCodec is a static layout and is not instantiable");
    }

    /**
     * Renders one row's composite key as the concatenated key image a cursor carries.
     *
     * @param balance the row whose key is wanted; must not be {@code null}
     * @return the concatenated key image
     * @throws NullPointerException if {@code balance} is {@code null}
     */
    public static String image(final TransactionCategoryBalance balance) {
        Objects.requireNonNull(balance, "balance must not be null");
        return image(balance.getTrancatAcctId(), balance.getTrancatTypeCd(),
                balance.getTrancatCd());
    }

    /**
     * Renders a composite key from its three parts, in cluster-key order.
     *
     * <p>The parts are concatenated exactly as supplied - never trimmed, padded or case folded -
     * because the stored columns are fixed-width fields and any adjustment here would produce a cursor
     * value that no longer names a stored key.
     *
     * @param accountId    the account-identifier part; must not be {@code null}
     * @param typeCode     the type-code part; must not be {@code null}
     * @param categoryCode the category-code part; must not be {@code null}
     * @return the concatenated key image
     * @throws NullPointerException if any part is {@code null}
     */
    public static String image(final String accountId, final String typeCode,
            final String categoryCode) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(typeCode, "typeCode must not be null");
        Objects.requireNonNull(categoryCode, "categoryCode must not be null");
        return accountId + typeCode + categoryCode;
    }

    /**
     * The account-identifier part of a key image.
     *
     * @param keyImage the key image, which may be shorter than the declared key length; must not be
     *                 {@code null}
     * @return the account-identifier part, or the empty string when the image does not reach it
     * @throws NullPointerException if {@code keyImage} is {@code null}
     */
    public static String accountIdOf(final String keyImage) {
        return part(keyImage, ACCOUNT_ID_OFFSET, ACCOUNT_ID_LENGTH);
    }

    /**
     * The type-code part of a key image.
     *
     * @param keyImage the key image; must not be {@code null}
     * @return the type-code part, or the empty string when the image does not reach it
     * @throws NullPointerException if {@code keyImage} is {@code null}
     */
    public static String typeCodeOf(final String keyImage) {
        return part(keyImage, TYPE_CODE_OFFSET, TYPE_CODE_LENGTH);
    }

    /**
     * The category-code part of a key image: everything the image holds beyond the type code.
     *
     * <p>Unbounded on purpose - see the class note on why this part is not truncated to its declared
     * width.
     *
     * @param keyImage the key image; must not be {@code null}
     * @return the category-code part, or the empty string when the image does not reach it
     * @throws NullPointerException if {@code keyImage} is {@code null}
     */
    public static String categoryCodeOf(final String keyImage) {
        Objects.requireNonNull(keyImage, "keyImage must not be null");
        if (keyImage.length() <= CATEGORY_CODE_OFFSET) {
            return UNCONSTRAINED;
        }
        return keyImage.substring(CATEGORY_CODE_OFFSET);
    }

    /**
     * Slices one fixed-width part out of a key image, tolerating an image that stops short of it.
     *
     * @param keyImage the key image; must not be {@code null}
     * @param offset   zero-based offset of the part
     * @param width    declared width of the part
     * @return the part, or the empty string when the image does not reach the offset
     */
    private static String part(final String keyImage, final int offset, final int width) {
        Objects.requireNonNull(keyImage, "keyImage must not be null");
        if (keyImage.length() <= offset) {
            return UNCONSTRAINED;
        }
        return keyImage.substring(offset, Math.min(keyImage.length(), offset + width));
    }
}
