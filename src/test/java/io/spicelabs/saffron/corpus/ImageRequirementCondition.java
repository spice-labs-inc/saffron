/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.AnnotatedElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * JUnit condition that evaluates {@link RequiresImage} annotations.
 *
 * <p>This condition checks if the test corpus contains an image matching
 * the specified requirements. If no matching image is available, the test
 * is skipped with an informative message.
 */
public class ImageRequirementCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED_BY_DEFAULT =
            ConditionEvaluationResult.enabled("@RequiresImage not present");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<AnnotatedElement> element = context.getElement();
        if (element.isEmpty()) {
            return ENABLED_BY_DEFAULT;
        }

        RequiresImage annotation = element.get().getAnnotation(RequiresImage.class);
        if (annotation == null) {
            return ENABLED_BY_DEFAULT;
        }

        return evaluateRequirements(annotation);
    }

    /**
     * Evaluates the requirements specified in the annotation.
     */
    private ConditionEvaluationResult evaluateRequirements(RequiresImage annotation) {
        CorpusManifest manifest = TestCorpusUtils.manifest();

        // Filter images by requirements
        Optional<CorpusImage> match = manifest.images().stream()
                .filter(img -> Files.exists(TestCorpusUtils.corpusDirectory().resolve(img.path())))
                .filter(img -> matchesFilesystem(img, annotation.filesystem()))
                .filter(img -> matchesFormat(img, annotation.format()))
                .filter(img -> matchesSize(img, annotation.minSizeMB()))
                .filter(img -> matchesTier(img, annotation.ciTier()))
                .filter(img -> matchesEra(img, annotation.legacy(), annotation.modern()))
                .findFirst();

        if (match.isPresent()) {
            CorpusImage img = match.get();
            return ConditionEvaluationResult.enabled(
                    String.format("Found matching image: %s (%s, %s, %d MB)",
                            img.id(), img.filesystem(), img.format(),
                            img.actualSizeBytes() / (1024 * 1024)));
        }

        // Build skip message
        String reason = buildSkipMessage(annotation);
        return ConditionEvaluationResult.disabled(reason);
    }

    private boolean matchesFilesystem(CorpusImage img, String filesystem) {
        if (filesystem.isEmpty()) {
            return true;
        }
        return filesystem.equalsIgnoreCase(img.filesystem());
    }

    private boolean matchesFormat(CorpusImage img, String format) {
        if (format.isEmpty()) {
            return true;
        }
        return format.equalsIgnoreCase(img.format());
    }

    private boolean matchesSize(CorpusImage img, long minSizeMB) {
        if (minSizeMB <= 0) {
            return true;
        }
        long minBytes = minSizeMB * 1024 * 1024;
        return img.actualSizeBytes() >= minBytes || img.virtualSizeBytes() >= minBytes;
    }

    private boolean matchesTier(CorpusImage img, String ciTier) {
        if (ciTier.isEmpty()) {
            return true;
        }
        return ciTier.equalsIgnoreCase(img.ciTier());
    }

    private boolean matchesEra(CorpusImage img, boolean legacy, boolean modern) {
        if (!legacy && !modern) {
            return true; // No era restriction
        }
        if (legacy && modern) {
            return true; // Both allowed (no restriction)
        }
        if (legacy) {
            return img.isLegacy();
        }
        return !img.isLegacy();
    }

    private String buildSkipMessage(RequiresImage annotation) {
        if (!annotation.reason().isEmpty()) {
            return annotation.reason();
        }

        StringBuilder sb = new StringBuilder("Skipping: no image found with requirements - ");

        boolean first = true;
        if (!annotation.filesystem().isEmpty()) {
            sb.append("filesystem=").append(annotation.filesystem());
            first = false;
        }
        if (!annotation.format().isEmpty()) {
            if (!first) sb.append(", ");
            sb.append("format=").append(annotation.format());
            first = false;
        }
        if (annotation.minSizeMB() > 0) {
            if (!first) sb.append(", ");
            sb.append("minSizeMB=").append(annotation.minSizeMB());
            first = false;
        }
        if (!annotation.ciTier().isEmpty()) {
            if (!first) sb.append(", ");
            sb.append("ciTier=").append(annotation.ciTier());
            first = false;
        }
        if (annotation.legacy()) {
            if (!first) sb.append(", ");
            sb.append("era=legacy");
            first = false;
        }
        if (annotation.modern()) {
            if (!first) sb.append(", ");
            sb.append("era=modern");
        }

        return sb.toString();
    }
}
