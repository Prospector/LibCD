package io.github.cottonmc.libcd.tweaker;

import com.google.common.collect.Sets;
import io.github.cottonmc.libcd.impl.MatchTypeSetter;
import io.github.cottonmc.libcd.util.NbtMatchType;
import io.netty.buffer.Unpooled;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.tag.ItemTags;
import net.minecraft.tag.Tag;
import net.minecraft.util.DefaultedList;
import net.minecraft.util.Identifier;
import net.minecraft.util.PacketByteBuf;

import java.util.*;

/**
 * Helper class to make public versions private recipe methods
 */
public class RecipeParser {

	/**
	 * Get an Ingredient from a string item or tag id.
	 * @param input The id to use, with a # at the front if it's a tag or -> between two ids for a getter
	 * @return the Ingredient for the given id
	 */
	public static Ingredient processIngredient(Object input) throws TweakerSyntaxException {
		if (input instanceof Ingredient) return (Ingredient)input;
		else if (input instanceof String) {
			String in = (String)input;
			int index = in.indexOf('{');
			String nbt = "";
			NbtMatchType type = NbtMatchType.NONE;
			List<ItemStack> stacks = new ArrayList<>();
			if (index != -1) {
				int andIndex = in.indexOf('&');
				if (andIndex != -1) {
					type = NbtMatchType.forName(in.substring(andIndex+1));
					in = in.substring(0, andIndex);
				}
				nbt = in.substring(index);
				in = in.substring(0, index);
			}
			if (in.indexOf('#') == 0) {
				String tag = in.substring(1);
				Tag<Item> itemTag = ItemTags.getContainer().get(new Identifier(tag));
				if (itemTag == null) throw new TweakerSyntaxException("Failed to get item tag for input: " + in);
				for (Item item : itemTag.values()) {
					stacks.add(new ItemStack(item));
				}
			} else if (in.contains("->")) {
				ItemStack stack = TweakerUtils.INSTANCE.getSpecialStack(in);
				if (stack.isEmpty())
					throw new TweakerSyntaxException("Failed to get special stack for input: " + in);
				stacks.add(stack);
				type = NbtMatchType.EXACT;
			} else {
				Item item = TweakerUtils.INSTANCE.getItem(in);
				if (item == Items.AIR) throw new TweakerSyntaxException("Failed to get item for input: " + in);
				stacks.add(new ItemStack(item));
			}
			if (!nbt.equals("")) {
				for (ItemStack stack : stacks) {
					if (!stack.hasTag() || stack.getTag().isEmpty()) TweakerUtils.INSTANCE.addNbtToStack(stack, nbt);
				}
			}
			Ingredient ret = hackStackIngredients(stacks.toArray(new ItemStack[]{}));
			((MatchTypeSetter)(Object)ret).libcd_setMatchType(type);
			return ret;
		}
		else throw new TweakerSyntaxException("Illegal object passed to recipe parser of type " + input.getClass());
	}

	/**
	 * Split a getter string into the ids of the getter/id.
	 * @param base The base getter string to split.
	 * @return A two-item array of the two parts of the getter.
	 */
	static String[] processGetter(String base) {
		String[] split = new String[2];
		int splitter = base.indexOf("->");
		split[0] = base.substring(0, splitter);
		split[1] = base.substring(splitter + 2);
		return split;
	}

	/**
	 * Process a grid of inputs CraftTweaker-style.
	 * @param inputs The array of string arrays to process inputs from
	 * @return The inputs converted into a single string array if the grid is valid
	 */
	public static Object[] processGrid(Object[][] inputs) throws TweakerSyntaxException {
		if (inputs.length > 3) throw new TweakerSyntaxException("Invalid pattern: too many columns, 3 is maximum");
		if (inputs.length == 0) throw new TweakerSyntaxException("Invalid pattern: empty pattern is not allowed");
		int width = inputs[0].length;
		Object[] output = new Object[inputs.length * inputs[0].length];
		for (int i = 0; i < inputs.length; i++) {
			Object[] row = inputs[i];
			if (row.length > 3) throw new TweakerSyntaxException("Invalid pattern: too many columns, 3 is maximum");
			if (row.length != width) throw new TweakerSyntaxException("Invalid pattern: each row must be the same width");
			for (int j = 0; j < width; j++) {
				output[j + inputs.length * i] = inputs[i][j];
			}
		}
		return output;
	}

	/**
	 * validate and parse a recipe pattern.
	 * @param pattern up to three strings of up to three characters each for the pattern
	 * @return processed pattern
	 */
	public static String[] processPattern(String... pattern) throws TweakerSyntaxException {
		if (pattern.length > 3) {
			throw new TweakerSyntaxException("Invalid pattern: too many rows, 3 is maximum");
		} else if (pattern.length == 0) {
			throw new TweakerSyntaxException("Invalid pattern: empty pattern not allowed");
		} else {
			for (int i = 0; i < pattern.length; i++) {
				String row = pattern[i];
				if (row.length() > 3) {
					throw new TweakerSyntaxException("Invalid pattern: too many columns, 3 is maximum");
				}

				if (i > 0 && pattern[0].length() != row.length()) {
					throw new TweakerSyntaxException("Invalid pattern: each row must be the same width");
				}

				pattern[i] = row;
			}
			int nextIndex = 2147483647;
			int highIndex = 0;
			int checked = 0;
			int sinceLastEmpty = 0;

			for (int i = 0; i < pattern.length; ++i) {
				String input = pattern[i];
				nextIndex = Math.min(nextIndex, findNextIngredient(input));
				int lastIndex = findNextIngredientReverse(input);
				highIndex = Math.max(highIndex, lastIndex);
				if (lastIndex < 0) {
					if (checked == i) {
						++checked;
					}

					++sinceLastEmpty;
				} else {
					sinceLastEmpty = 0;
				}
			}

			if (pattern.length == sinceLastEmpty) {
				return new String[0];
			} else {
				String[] combined = new String[pattern.length - sinceLastEmpty - checked];

				for (int i = 0; i < combined.length; ++i) {
					combined[i] = pattern[i + checked].substring(nextIndex, highIndex + 1);
				}

				return combined;
			}
		}
	}

	/**
	 * Process dictionaries into a Recipe-readable form.
	 * @param dictionary a map of keys to values for a recipe to parse.
	 * @return A map of string keys to ingredient values that a Recipe can read.
	 */
	public static Map<String, Ingredient> processDictionary(Map<String, Object> dictionary) throws TweakerSyntaxException {
		Map<String, Ingredient> map = new HashMap<>();
		for (Map.Entry<String, Object> entry : dictionary.entrySet()) {
			if (entry.getKey().length() != 1) {
				throw new TweakerSyntaxException("Invalid key entry: '" + entry.getKey() + "' is an invalid symbol (must be 1 character only).");
			}

			if (" ".equals(entry.getKey())) {
				throw new TweakerSyntaxException("Invalid key entry: ' ' is a reserved symbol.");
			}
			map.put(entry.getKey(), processIngredient(entry.getValue()));
		}
		map.put(" ", Ingredient.EMPTY);
		return map;
	}

	/**
	 * Compile a pattern and dictionary into a full ingredient list.
	 * @param pattern A patern parsed by processPattern.
	 * @param dictionary A dictionary parsed by processDictionary.
	 * @param x How many columns there are.
	 * @param y How many rows there are.
	 * @return A defaulted list of ingredients.
	 */
	public static DefaultedList<Ingredient> getIngredients(String[] pattern, Map<String, Ingredient> dictionary, int x, int y) throws TweakerSyntaxException {
		DefaultedList<Ingredient> ingredients = DefaultedList.create(x * y, Ingredient.EMPTY);
		Set<String> keys = Sets.newHashSet(dictionary.keySet());
		keys.remove(" ");

		for(int i = 0; i < pattern.length; i++) {
			for(int j = 0; j < pattern[i].length(); j++) {
				String key = pattern[i].substring(j, j + 1);
				Ingredient ingredient = dictionary.get(key);
				if (ingredient == null) {
					throw new TweakerSyntaxException("Pattern references symbol '" + key + "' but it's not defined in the key");
				}

				keys.remove(key);
				ingredients.set(j + x * i, ingredient);
			}
		}

		if (!keys.isEmpty()) {
			throw new TweakerSyntaxException("Key defines symbols that aren't used in pattern: " + keys);
		} else {
			return ingredients;
		}
	}

	/**
	 * Skip forwards through a recipe row to find an ingredient key
	 * @param input a recipe row to parse
	 * @return index for the next ingredient character
	 */
	private static int findNextIngredient(String input) {
		int i;
		for (i = 0; i < input.length() && input.charAt(i) == ' '; i++) { }
		return i;
	}

	/**
	 * Skip backwards through a recipe row to find an ingredient key
	 * @param input a recipe row to parse
	 * @return index for the next ingredient character
	 */
	private static int findNextIngredientReverse(String input) {
		int i;
		for (i = input.length() - 1; i >= 0 && input.charAt(i) == ' '; i--) { }
		return i;
	}

	/**
	 * Thanks, ProGuard! The `Ingredient.ofStacks()` method is currently only in the client environment,
	 * so I have to write this ugly, terrible hack to make it work!
	 * Serializes the input stacks into a PacketByteBuf,
	 * then tricks the Ingredient class into deserializing them.
	 * @param stacks The input stacks to support.
	 * @return The ingredient object for the input stacks.
	 */
	public static Ingredient hackStackIngredients(ItemStack...stacks) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		buf.writeVarInt(stacks.length);
		for (ItemStack stack : stacks) {
			buf.writeItemStack(stack);
		}
		return Ingredient.fromPacket(buf);
	}
}