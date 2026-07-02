package archive;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
public class AttributeValidatorTest {

    private static CompoundTag entityWithAttributeId(String id) {
        CompoundTag attr = new CompoundTag();
        attr.putString("id", id);
        return entityWith(attr);
    }

    private static CompoundTag entityWith(CompoundTag... attrs) {
        ListTag list = new ListTag();
        for (CompoundTag attr : attrs) list.add(attr);
        CompoundTag entity = new CompoundTag();
        entity.put("attributes", list);
        return entity;
    }

    @Test
    void entityWithoutAttributesIsClean() {
        assertFalse(AttributeValidator.entityHasInvalidAttribute(new CompoundTag()));
        assertFalse(AttributeValidator.entityHasInvalidAttribute(entityWith()));
    }

    @Test
    void parseableAttributeIdIsClean() {
        assertFalse(AttributeValidator.entityHasInvalidAttribute(entityWithAttributeId("minecraft:max_health")));
    }

    @Test
    void unparseableAttributeIdFlags() {
        assertTrue(AttributeValidator.entityHasInvalidAttribute(entityWithAttributeId("Not A Valid Id!")));
    }

    @Test
    void emptyOrMissingIdIsIntentionallyNotFlagged() {
        // Documented: empty/missing id fails the vanilla codec for a different
        // reason and is a different dirt class; only present-but-unparseable counts.
        assertFalse(AttributeValidator.entityHasInvalidAttribute(entityWithAttributeId("")));
        assertFalse(AttributeValidator.entityHasInvalidAttribute(entityWith(new CompoundTag())));
    }

    @Test
    void unparseableModifierIdFlagsThroughNesting() {
        CompoundTag modifier = new CompoundTag();
        modifier.putString("id", "Bad Modifier Id");
        ListTag modifiers = new ListTag();
        modifiers.add(modifier);
        CompoundTag attr = new CompoundTag();
        attr.putString("id", "minecraft:scale");
        attr.put("modifiers", modifiers);
        assertTrue(AttributeValidator.entityHasInvalidAttribute(entityWith(attr)));
    }

    @Test
    void parseableModifierIdIsClean() {
        CompoundTag modifier = new CompoundTag();
        modifier.putString("id", "minecraft:some_modifier");
        ListTag modifiers = new ListTag();
        modifiers.add(modifier);
        CompoundTag attr = new CompoundTag();
        attr.putString("id", "minecraft:scale");
        attr.put("modifiers", modifiers);
        assertFalse(AttributeValidator.entityHasInvalidAttribute(entityWith(attr)));
    }
}
