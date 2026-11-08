package dev.lyricmod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Helps you make an .lrc file inside the game, by "tapping along" to the song.
 *
 * How it works:
 *  1. You put mysong.ogg and mysong.txt (plain lyrics, one line per line)
 *     in config/lyricmod.
 *  2. /lyrics record mysong starts the song. The action bar (the text above
 *     the hotbar) shows the next line.
 *  3. Each time that line starts being sung, you press the "Mark lyric line"
 *     key (K by default, changeable in Options > Controls). The line appears
 *     right away, so you can see your timing.
 *  4. After the last line, one more press marks the end and saves mysong.lrc.
 *     If the song ends first, what you marked so far is saved.
 *
 * /lyrics stop (or playing another song) cancels without saving.
 */
public final class LyricRecorder {

	/** The key the player presses to mark a line. */
	private static KeyMapping markKey;

	/** Name of the song being recorded, or null when not recording. */
	private static String songName;

	/** Lines from the .txt file, and how many of them are marked so far. */
	private static List<String> textLines;
	private static final List<Lyrics.Line> marked = new ArrayList<>();

	/** Tags like [ti:...] copied from the .txt file into the .lrc. */
	private static List<String> tagLines;

	/** True once the music has actually started (it loads for a moment first). */
	private static boolean started;

	/** Ticks until we refresh the action bar text (it fades after a few seconds). */
	private static int hintCooldown;

	private LyricRecorder() {
	}

	/** Registers the "Mark lyric line" key. Called once at startup. */
	public static void registerKey() {
		markKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.lyricmod.mark_line",   // name, translated in assets/lyricmod/lang/en_us.json
				InputConstants.KEY_K,       // default key
				KeyMapping.Category.MISC)); // shown under "Miscellaneous" in Controls
	}

	public static boolean isRecording() {
		return songName != null;
	}

	/**
	 * Starts recording: reads name.txt, then plays name.ogg.
	 *
	 * @throws IOException with a player-friendly message if files are missing or wrong
	 */
	public static void start(String name) throws IOException {
		Path textFile = LyricFiles.songFile(name, ".txt");
		Path oggFile = LyricFiles.songFile(name, ".ogg");

		List<String> lines = new ArrayList<>();
		List<String> tags = new ArrayList<>();
		String content = new String(Files.readAllBytes(textFile), StandardCharsets.UTF_8);
		if (content.startsWith("﻿")) {
			content = content.substring(1); // invisible "byte order mark" some editors add
		}
		for (String line : content.split("\\R")) {
			String clean = line.strip();
			if (clean.isEmpty()) {
				continue; // blank lines are just spacing in the .txt
			}
			if (clean.matches("\\[[a-zA-Z]+:.*]")) {
				tags.add(clean); // e.g. [ti:Title] or [ar:Artist], copied as-is
			} else {
				lines.add(clean);
			}
		}
		if (lines.isEmpty()) {
			throw new IOException(name + ".txt has no lyric lines");
		}

		SongPlayer.stop(); // also cancels any earlier recording (see cancel())
		songName = name;
		textLines = lines;
		tagLines = tags;
		marked.clear();
		started = false;

		Minecraft client = Minecraft.getInstance();
		SongPlayer.play(oggFile, previewLyrics(),
				() -> {
					started = true;
					hintCooldown = 0;
					message(client, "Recording " + name + ": press the \"Mark lyric line\" key ("
							+ markKey.getTranslatedKeyMessage().getString() + ") when each line starts.");
				},
				error -> {
					cancel();
					message(client, error);
				});
	}

	/** Stops recording without saving. Safe to call when not recording. */
	public static void cancel() {
		Minecraft client = Minecraft.getInstance();
		if (songName != null && client.player != null) {
			client.player.sendOverlayMessage(Component.empty()); // remove the "REC" hint
		}
		songName = null;
		textLines = null;
		marked.clear();
		started = false;
	}

	/** Called every game tick (20 times per second). */
	public static void tick(Minecraft client) {
		if (!isRecording()) {
			// Throw away key presses made while not recording.
			while (markKey.consumeClick()) {
				// nothing
			}
			return;
		}
		if (!started) {
			return; // still loading the song
		}
		if (!SongPlayer.isPlaying()) {
			// The song reached its end: save what we have.
			finish(client, -1);
			return;
		}

		while (markKey.consumeClick()) {
			mark(client);
			if (!isRecording()) {
				return; // that press finished the recording
			}
		}

		// Keep the action bar hint visible.
		if (--hintCooldown <= 0 && client.player != null) {
			hintCooldown = 40; // every 2 seconds
			client.player.sendOverlayMessage(Component.literal(hintText()));
		}
	}

	/** One key press: mark the next line, or the end if all lines are marked. */
	private static void mark(Minecraft client) {
		long now = SongPlayer.positionMs();
		if (marked.size() < textLines.size()) {
			marked.add(new Lyrics.Line(now, textLines.get(marked.size()), List.of()));
			// Show the lines marked so far, so you see your timing immediately.
			SongPlayer.setLyrics(previewLyrics());
			hintCooldown = 0; // update the hint right away
		} else {
			finish(client, now);
		}
	}

	/** What the action bar says: the next line to mark. */
	private static String hintText() {
		if (marked.size() < textLines.size()) {
			return "● REC " + (marked.size() + 1) + "/" + textLines.size() + " next: " + textLines.get(marked.size());
		}
		return "● REC all lines marked: press again when the singing ends";
	}

	/** The lyrics marked so far, for the live preview. */
	private static Lyrics previewLyrics() {
		return new Lyrics(songName, "", List.copyOf(marked));
	}

	/**
	 * Writes name.lrc and stops recording.
	 *
	 * @param endMs when the singing ended (-1 if unknown, e.g. the song ran out)
	 */
	private static void finish(Minecraft client, long endMs) {
		String name = songName;
		List<Lyrics.Line> lines = List.copyOf(marked);
		List<String> tags = tagLines;
		int totalLines = textLines.size();
		cancel();
		SongPlayer.stop();

		if (lines.isEmpty()) {
			message(client, "Recording ended: no lines were marked, nothing saved.");
			return;
		}

		StringBuilder lrc = new StringBuilder();
		boolean hasTitle = tags.stream().anyMatch(t -> t.toLowerCase().startsWith("[ti:"));
		if (!hasTitle) {
			lrc.append("[ti:").append(name).append("]\n");
		}
		for (String tag : tags) {
			lrc.append(tag).append('\n');
		}
		for (Lyrics.Line line : lines) {
			lrc.append('[').append(formatTime(line.timeMs())).append(']').append(line.text()).append('\n');
		}
		if (endMs >= 0) {
			lrc.append('[').append(formatTime(endMs)).append("]\n"); // empty line = singing ends
		}

		try {
			Path folder = LyricFiles.folder();
			Path lrcFile = folder.resolve(name + ".lrc"); // name was checked by LyricFiles.songFile
			if (Files.exists(lrcFile)) {
				// Never destroy someone's work: keep the old file as a backup.
				Files.move(lrcFile, folder.resolve(name + ".lrc.bak"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(lrcFile, lrc.toString(), StandardCharsets.UTF_8);
			String missing = lines.size() < totalLines ? " (" + (totalLines - lines.size()) + " lines were not marked)" : "";
			message(client, "Saved " + name + ".lrc with " + lines.size() + " lines" + missing
					+ ". Try it: /lyrics play " + name);
		} catch (IOException e) {
			message(client, "Could not save " + name + ".lrc: " + e.getMessage());
		}
	}

	/** 75250 ms -> "01:15.25" (minutes:seconds.hundredths), the .lrc time format. */
	static String formatTime(long ms) {
		long minutes = ms / 60_000;
		long seconds = (ms / 1000) % 60;
		long hundredths = (ms % 1000) / 10;
		return String.format("%02d:%02d.%02d", minutes, seconds, hundredths);
	}

	private static void message(Minecraft client, String text) {
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal(text));
		}
	}
}
