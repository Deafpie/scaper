package com.scaperplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import okhttp3.*;

import javax.imageio.ImageIO;
import net.runelite.client.audio.AudioPlayer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class CaseOpenOverlay extends Overlay
{
	private static final String API_URL = "https://api.scaper.icu";
	private static final String SITE_URL = "https://scaper.icu";
	private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

	// Rarity colors
	private static final Map<String, Color> RARITY_COLORS = Map.of(
		"common", new Color(158, 158, 158),
		"uncommon", new Color(76, 175, 80),
		"rare", new Color(90, 173, 255),
		"epic", new Color(206, 147, 216),
		"legendary", new Color(255, 215, 0)
	);

	// Animation states
	public enum State { HIDDEN, CASE_DISPLAY, CASE_OPENING, ROULETTE_SPINNING, REVEAL, DONE }

	private final Client client;
	private final OkHttpClient httpClient;

	private volatile State state = State.HIDDEN;

	// Case data
	private String caseId;
	private String caseName;
	private BufferedImage caseImage;
	private BufferedImage caseOpenImage;

	// Roulette data
	private List<StripItem> strip = new ArrayList<>();
	private int winnerIndex;
	private StripItem winner;
	private int winnerWear;

	// Animation
	private long animStartTime;
	private double scrollOffset;
	private static final int TILE_WIDTH = 120;
	private static final int TILE_HEIGHT = 120;
	private static final int TILE_GAP = 8;
	private static final long SPIN_DURATION_MS = 4500;

	// Image cache
	private final ConcurrentHashMap<String, BufferedImage> imageCache = new ConcurrentHashMap<>();

	// Sounds
	private AudioPlayer audioPlayer;

	// Click state
	private boolean caseClicked = false;

	public CaseOpenOverlay(Client client, OkHttpClient httpClient, AudioPlayer audioPlayer)
	{
		this.client = client;
		this.httpClient = httpClient;
		this.audioPlayer = audioPlayer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(999);
	}

	public boolean isActive()
	{
		return state != State.HIDDEN;
	}

	public void startCaseOpen(String caseId, String caseName, String caseImageUrl, String caseOpenImageUrl)
	{
		this.caseId = caseId;
		this.caseName = caseName;
		this.caseClicked = false;
		this.strip.clear();
		this.winner = null;

		// Load case images async
		loadImageAsync(caseImageUrl, img -> this.caseImage = img);
		loadImageAsync(caseOpenImageUrl, img -> this.caseOpenImage = img);

		state = State.CASE_DISPLAY;
	}

	public void handleMouseClick(MouseEvent e)
	{
		if (state == State.HIDDEN) return;

		if (state == State.CASE_DISPLAY && !caseClicked)
		{
			caseClicked = true;
			openCaseOnServer();
		}
		else if (state == State.CASE_OPENING)
		{
			// Skip to roulette immediately
			scrollOffset = 0;
			animStartTime = System.currentTimeMillis();
			state = State.ROULETTE_SPINNING;
			playSound("/ticking_sound.wav");
		}
		else if (state == State.ROULETTE_SPINNING)
		{
			// Skip to reveal immediately
			// ticking sound ends naturally
			playSound("/reveal_sound.wav");
			state = State.REVEAL;
			animStartTime = System.currentTimeMillis();
		}
		else if (state == State.REVEAL || state == State.DONE)
		{
			// Dismiss
			state = State.HIDDEN;
			// ticking sound ends naturally
		}
	}

	private void openCaseOnServer()
	{
		String rsn = null;
		if (client.getLocalPlayer() != null)
		{
			rsn = client.getLocalPlayer().getName();
		}
		if (rsn == null)
		{
			state = State.HIDDEN;
			return;
		}

		final String playerRsn = rsn;
		new Thread(() -> {
			try
			{
				JsonObject body = new JsonObject();
				body.addProperty("rsn", playerRsn);
				body.addProperty("caseId", caseId);

				Request request = new Request.Builder()
					.url(API_URL + "/api/cases/open")
					.post(RequestBody.create(JSON_TYPE, body.toString()))
					.build();

				try (Response response = httpClient.newCall(request).execute())
				{
					String responseBody = response.body() != null ? response.body().string() : "";
					if (!response.isSuccessful())
					{
						log.warn("Case open failed: {}", responseBody);
						state = State.HIDDEN;
						return;
					}

					JsonObject data = new JsonParser().parse(responseBody).getAsJsonObject();
					winnerIndex = data.get("winnerIndex").getAsInt();

					JsonObject winnerObj = data.getAsJsonObject("winner");
					winner = new StripItem(
						winnerObj.get("id").getAsString(),
						winnerObj.get("name").getAsString(),
						winnerObj.get("rarity").getAsString(),
						winnerObj.get("image").getAsString()
					);
					winnerWear = winnerObj.get("wearHundredths").getAsInt();

					JsonArray stripArr = data.getAsJsonArray("strip");
					strip.clear();
					for (JsonElement el : stripArr)
					{
						JsonObject item = el.getAsJsonObject();
						StripItem si = new StripItem(
							item.get("id").getAsString(),
							item.get("name").getAsString(),
							item.get("rarity").getAsString(),
							item.get("image").getAsString()
						);
						strip.add(si);
						// Pre-load images
						loadImageAsync(si.imageUrl, img -> {});
					}

					// Show the open case image for 1 second before roulette
					playSound("/case_open_sound.wav");
					animStartTime = System.currentTimeMillis();
					state = State.CASE_OPENING;
				}
			}
			catch (Exception ex)
			{
				log.error("Case open error", ex);
				state = State.HIDDEN;
			}
		}).start();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (state == State.HIDDEN) return null;

		int w = client.getCanvasWidth();
		int h = client.getCanvasHeight();

		// Dim background
		g.setColor(new Color(0, 0, 0, 200));
		g.fillRect(0, 0, w, h);

		switch (state)
		{
			case CASE_DISPLAY:
				renderCaseDisplay(g, w, h);
				break;
			case CASE_OPENING:
				renderCaseOpening(g, w, h);
				break;
			case ROULETTE_SPINNING:
				renderRoulette(g, w, h);
				break;
			case REVEAL:
			case DONE:
				renderReveal(g, w, h);
				break;
		}

		return new Dimension(w, h);
	}

	private void renderCaseDisplay(Graphics2D g, int w, int h)
	{
		BufferedImage img = caseClicked ? caseOpenImage : caseImage;
		if (img == null) img = caseImage;

		int imgSize = Math.min(300, Math.min(w, h) / 2);
		int x = (w - imgSize) / 2;
		int y = (h - imgSize) / 2 - 30;

		if (img != null)
		{
			g.drawImage(img, x, y, imgSize, imgSize, null);
		}
		else
		{
			// Placeholder
			g.setColor(new Color(40, 40, 40));
			g.fillRoundRect(x, y, imgSize, imgSize, 16, 16);
		}

		// Case name
		g.setFont(new Font("Arial", Font.BOLD, 22));
		g.setColor(Color.WHITE);
		FontMetrics fm = g.getFontMetrics();
		int textX = (w - fm.stringWidth(caseName)) / 2;
		g.drawString(caseName, textX, y + imgSize + 35);

		// Instruction
		if (!caseClicked)
		{
			g.setFont(new Font("Arial", Font.PLAIN, 14));
			g.setColor(new Color(200, 200, 200));
			fm = g.getFontMetrics();
			String hint = "Click to open";
			g.drawString(hint, (w - fm.stringWidth(hint)) / 2, y + imgSize + 60);
		}
		else
		{
			g.setFont(new Font("Arial", Font.PLAIN, 14));
			g.setColor(new Color(212, 160, 23));
			fm = g.getFontMetrics();
			String hint = "Opening...";
			g.drawString(hint, (w - fm.stringWidth(hint)) / 2, y + imgSize + 60);
		}

		// Close button
		renderCloseButton(g, w);
	}

	private void renderCaseOpening(Graphics2D g, int w, int h)
	{
		BufferedImage img = caseOpenImage != null ? caseOpenImage : caseImage;
		int imgSize = Math.min(300, Math.min(w, h) / 2);
		int x = (w - imgSize) / 2;
		int y = (h - imgSize) / 2 - 30;

		if (img != null)
		{
			g.drawImage(img, x, y, imgSize, imgSize, null);
		}

		g.setFont(new Font("Arial", Font.BOLD, 22));
		g.setColor(Color.WHITE);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(caseName, (w - fm.stringWidth(caseName)) / 2, y + imgSize + 35);

		g.setFont(new Font("Arial", Font.PLAIN, 14));
		g.setColor(new Color(212, 160, 23));
		fm = g.getFontMetrics();
		g.drawString("Opening...", (w - fm.stringWidth("Opening...")) / 2, y + imgSize + 60);

		// After 1 second, transition to roulette
		long elapsed = System.currentTimeMillis() - animStartTime;
		if (elapsed >= 1000)
		{
			scrollOffset = 0;
			animStartTime = System.currentTimeMillis();
			state = State.ROULETTE_SPINNING;
			new Thread(() -> {
				try { Thread.sleep(300); } catch (InterruptedException ignored) {}
				playSound("/ticking_sound.wav");
			}).start();
		}
	}

	private void renderRoulette(Graphics2D g, int w, int h)
	{
		long elapsed = System.currentTimeMillis() - animStartTime;
		double progress = Math.min(1.0, (double) elapsed / SPIN_DURATION_MS);

		// Easing: cubic ease-out
		double eased = 1.0 - Math.pow(1.0 - progress, 3);

		// Total scroll distance: from 0 to winnerIndex * tile stride, centered
		int tileStride = TILE_WIDTH + TILE_GAP;
		double targetOffset = winnerIndex * tileStride;
		scrollOffset = eased * targetOffset;

		// Roulette strip area
		int stripY = (h - TILE_HEIGHT) / 2;
		int centerX = w / 2;

		// Clip to visible area
		Shape oldClip = g.getClip();
		int visibleWidth = Math.min(w - 40, tileStride * 5);
		int clipX = (w - visibleWidth) / 2;
		g.setClip(clipX, stripY - 10, visibleWidth, TILE_HEIGHT + 20);

		// Draw tiles
		for (int i = 0; i < strip.size(); i++)
		{
			int tileX = (int) (centerX + i * tileStride - scrollOffset - TILE_WIDTH / 2);
			if (tileX > w || tileX + TILE_WIDTH < 0) continue;

			StripItem item = strip.get(i);
			Color rarityColor = RARITY_COLORS.getOrDefault(item.rarity, RARITY_COLORS.get("common"));

			// Tile background
			g.setColor(new Color(20, 20, 20));
			g.fill(new RoundRectangle2D.Float(tileX, stripY, TILE_WIDTH, TILE_HEIGHT, 8, 8));

			// Rarity border
			g.setColor(rarityColor);
			g.setStroke(new BasicStroke(2));
			g.draw(new RoundRectangle2D.Float(tileX, stripY, TILE_WIDTH, TILE_HEIGHT, 8, 8));

			// Item image
			BufferedImage itemImg = imageCache.get(item.imageUrl);
			if (itemImg != null)
			{
				int imgPad = 10;
				g.drawImage(itemImg, tileX + imgPad, stripY + imgPad,
					TILE_WIDTH - imgPad * 2, TILE_HEIGHT - imgPad * 2, null);
			}
		}

		g.setClip(oldClip);

		// Center marker (golden line)
		g.setColor(new Color(212, 168, 64));
		g.setStroke(new BasicStroke(3));
		g.drawLine(centerX, stripY - 15, centerX, stripY + TILE_HEIGHT + 15);

		// Marker triangle
		int[] triX = { centerX - 8, centerX + 8, centerX };
		int[] triY = { stripY - 18, stripY - 18, stripY - 8 };
		g.fillPolygon(triX, triY, 3);

		// Case name above
		g.setFont(new Font("Arial", Font.BOLD, 18));
		g.setColor(Color.WHITE);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(caseName, (w - fm.stringWidth(caseName)) / 2, stripY - 40);

		// Check if done
		if (progress >= 1.0)
		{
			// ticking sound ends naturally
			playSound("/reveal_sound.wav");
			state = State.REVEAL;
			animStartTime = System.currentTimeMillis();
		}
	}

	private void renderReveal(Graphics2D g, int w, int h)
	{
		if (winner == null)
		{
			state = State.DONE;
			return;
		}

		long elapsed = System.currentTimeMillis() - animStartTime;
		float alpha = Math.min(1f, elapsed / 500f);

		Color rarityColor = RARITY_COLORS.getOrDefault(winner.rarity, RARITY_COLORS.get("common"));

		// Glow background (radial gradient)
		int glowSize = 360;
		int cx = w / 2;
		int cy = h / 2 - 20;
		Paint oldPaint = g.getPaint();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.7f));
		g.setPaint(new java.awt.RadialGradientPaint(
			cx, cy, glowSize / 2f,
			new float[]{0f, 0.4f, 1f},
			new Color[]{
				new Color(rarityColor.getRed(), rarityColor.getGreen(), rarityColor.getBlue(), 180),
				new Color(rarityColor.getRed(), rarityColor.getGreen(), rarityColor.getBlue(), 60),
				new Color(rarityColor.getRed(), rarityColor.getGreen(), rarityColor.getBlue(), 0)
			}
		));
		g.fillOval(cx - glowSize / 2, cy - glowSize / 2, glowSize, glowSize);
		g.setPaint(oldPaint);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		// Winner image
		BufferedImage winnerImg = imageCache.get(winner.imageUrl);
		int imgSize = 200;
		int imgX = cx - imgSize / 2;
		int imgY = cy - imgSize / 2;

		if (winnerImg != null)
		{
			g.drawImage(winnerImg, imgX, imgY, imgSize, imgSize, null);
		}

		// Winner name
		g.setFont(new Font("Arial", Font.BOLD, 24));
		g.setColor(Color.WHITE);
		FontMetrics fm = g.getFontMetrics();
		String name = winner.name;
		g.drawString(name, (w - fm.stringWidth(name)) / 2, cy + imgSize / 2 + 35);

		// Rarity label
		g.setFont(new Font("Arial", Font.BOLD, 14));
		g.setColor(rarityColor);
		fm = g.getFontMetrics();
		String rarityLabel = winner.rarity.substring(0, 1).toUpperCase() + winner.rarity.substring(1);
		g.drawString(rarityLabel, (w - fm.stringWidth(rarityLabel)) / 2, cy + imgSize / 2 + 58);

		// Wear value
		g.setFont(new Font("Arial", Font.PLAIN, 12));
		g.setColor(new Color(180, 180, 180));
		fm = g.getFontMetrics();
		String wearText = "Wear: " + String.format("%.2f", winnerWear / 100.0);
		g.drawString(wearText, (w - fm.stringWidth(wearText)) / 2, cy + imgSize / 2 + 78);

		// Click to dismiss
		if (elapsed > 1500)
		{
			state = State.DONE;
			g.setFont(new Font("Arial", Font.PLAIN, 13));
			g.setColor(new Color(150, 150, 150));
			fm = g.getFontMetrics();
			String dismiss = "Click anywhere to close";
			g.drawString(dismiss, (w - fm.stringWidth(dismiss)) / 2, h - 40);
		}

		renderCloseButton(g, w);
	}

	private void renderCloseButton(Graphics2D g, int w)
	{
		g.setFont(new Font("Arial", Font.BOLD, 18));
		g.setColor(new Color(180, 180, 180));
		g.drawString("\u2715", w - 30, 30);
	}

	// â”€â”€ Image loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void loadImageAsync(String url, java.util.function.Consumer<BufferedImage> callback)
	{
		if (url == null || url.isEmpty()) return;
		if (imageCache.containsKey(url))
		{
			callback.accept(imageCache.get(url));
			return;
		}

		String fullUrl = url.startsWith("http") ? url
			: url.startsWith("/uploads/") ? API_URL + url
			: SITE_URL + url;

		new Thread(() -> {
			try
			{
				BufferedImage img = ImageIO.read(new URL(fullUrl));
				if (img != null)
				{
					imageCache.put(url, img);
					callback.accept(img);
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to load image: {}", fullUrl, e);
			}
		}).start();
	}

	// â”€â”€ Sound â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void playSound(String resource)
	{
		try
		{
			audioPlayer.play(getClass(), resource, 0f);
		}
		catch (Exception e)
		{
			log.debug("Could not play sound: {}", resource);
		}
	}

	// â”€â”€ Strip item data class â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	static class StripItem
	{
		final String id;
		final String name;
		final String rarity;
		final String imageUrl;

		StripItem(String id, String name, String rarity, String imageUrl)
		{
			this.id = id;
			this.name = name;
			this.rarity = rarity;
			this.imageUrl = imageUrl;
		}
	}
}
