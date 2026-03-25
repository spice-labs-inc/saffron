/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test method or class as requiring specific image characteristics.
 *
 * <p>This annotation enables manifest-driven test selection, where tests
 * are only executed if images matching the requirements are available in
 * the test corpus.
 *
 * <p>Example usage:
 * <pre>
 * @Test
 * @RequiresImage(filesystem = "xfs")
 * void readXfsFilesystem() throws Exception {
 *     Path image = TestCorpusUtils.findImageWithFilesystem("xfs").orElseThrow();
 *     // Test implementation...
 * }
 *
 * @Test
 * @RequiresImage(filesystem = "btrfs", minSizeMB = 100)
 * void readBtrfsWithMinimumSize() throws Exception {
 *     // Only runs if a btrfs image >= 100MB is available
 * }
 *
 * @Test
 * @RequiresImage(format = "vmdk", filesystem = "ntfs")
 * void readVmdkWithNtfs() throws Exception {
 *     // Only runs if a VMDK with NTFS is available
 * }
 * </pre>
 *
 * <p>Multiple requirements can be combined. The test will only run if
 * an image satisfying ALL requirements is available.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ImageRequirementCondition.class)
public @interface RequiresImage {

    /**
     * Required filesystem type (e.g., "xfs", "btrfs", "ntfs", "ext4", "fat32").
     *
     * @return the required filesystem type, or empty string for any
     */
    String filesystem() default "";

    /**
     * Required disk format (e.g., "qcow2", "vmdk", "vhd", "vdi", "raw").
     *
     * @return the required format, or empty string for any
     */
    String format() default "";

    /**
     * Minimum image size in megabytes.
     *
     * @return minimum size in MB, or 0 for no minimum
     */
    long minSizeMB() default 0;

    /**
     * Required CI tier ("quick", "standard", "full").
     *
     * @return the required CI tier, or empty string for any
     */
    String ciTier() default "";

    /**
     * Require legacy-era images (2005-2010).
     *
     * @return true if only legacy images should be considered
     */
    boolean legacy() default false;

    /**
     * Require modern-era images (2011+).
     *
     * @return true if only modern images should be considered
     */
    boolean modern() default false;

    /**
     * Custom reason message when the requirement is not met.
     *
     * @return the custom reason, or empty string for default message
     */
    String reason() default "";
}
