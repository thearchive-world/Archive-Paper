package archive;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;

/**
 * Detects the {@code invalid-attrs} dirt class on a single entity-like
 * compound: any {@code attributes[*].id} or
 * {@code attributes[*].modifiers[*].id} that fails
 * {@link Identifier#tryParse}. Matches the vanilla codec behaviour, which
 * wraps the whole list in one DataResult and discards the list on parse
 * failure (the mob then loads with default attribute base values).
 *
 * <p>Empty / missing id is intentionally NOT flagged: that fails the codec
 * for a different reason, surfaces as a different dirt class, and is
 * outside this predicate. Only a present-but-unparseable id counts.
 *
 * <p>Shared by {@link DirtNbtCleaner} (the strip rule) and
 * {@link AuditDirtyChunks} (the flag rule). Co-locating the predicate is
 * the load-bearing anti-drift fix: the cleaner's strip definition and the
 * audit's flag definition must remain byte-identical or the audit no
 * longer measures what the cleaner removes.
 */
final class AttributeValidator {
    private AttributeValidator() {}

    static boolean entityHasInvalidAttribute(CompoundTag entity) {
        ListTag attrs = entity.getListOrEmpty("attributes");
        for (int i = 0; i < attrs.size(); i++) {
            CompoundTag attr = attrs.getCompoundOrEmpty(i);
            if (isInvalidResourceLocation(attr.getStringOr("id", ""))) return true;
            ListTag mods = attr.getListOrEmpty("modifiers");
            for (int j = 0; j < mods.size(); j++) {
                if (isInvalidResourceLocation(mods.getCompoundOrEmpty(j).getStringOr("id", ""))) return true;
            }
        }
        return false;
    }

    private static boolean isInvalidResourceLocation(String id) {
        return !id.isEmpty() && Identifier.tryParse(id) == null;
    }
}
