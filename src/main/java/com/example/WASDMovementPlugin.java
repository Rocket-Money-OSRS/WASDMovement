package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Window;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "WASD Movement",
	description = "Map WASD keys to character movement using minimap clicks",
	tags = {"wasd", "movement", "minimap", "controls"}
)
public class WASDMovementPlugin extends Plugin implements KeyListener, MouseListener
{
	private static final int WALK_CLICK_RADIUS = 17;
	private static final int RUN_CLICK_RADIUS = 35;
	private static final int STOP_DELAY_TICKS = 3;

	private static final int FIXED_MINIMAP_GROUP = 548;
	private static final int FIXED_MINIMAP_CHILD = 22;

	private static final int RESIZABLE_MINIMAP_GROUP = 161;
	private static final int RESIZABLE_MINIMAP_CHILD = 28;

	private static final int RESIZABLE_BOTTOM_MINIMAP_GROUP = 164;
	private static final int RESIZABLE_BOTTOM_MINIMAP_CHILD = 28;

	@Inject
	private Client client;

	@Inject
	private WASDMovementConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ClientThread clientThread;

	private volatile boolean keyW, keyA, keyS, keyD;
	private volatile boolean wasMoving;
	private volatile boolean pendingStop;
	private volatile int stopDelayCounter;
	private volatile boolean isStopping;
	private volatile WorldPoint stopTargetTile;
	private Thread movementThread;
	private volatile boolean running;

	private double lastDx, lastDy;
	private boolean justReversed;

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(this);
		running = true;
		movementThread = new Thread(this::movementLoop);
		movementThread.start();
		log.info("WASD Movement started");
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		mouseManager.unregisterMouseListener(this);
		running = false;
		if (movementThread != null)
		{
			movementThread.interrupt();
		}
		keyW = keyA = keyS = keyD = false;
		wasMoving = false;
		pendingStop = false;
		stopDelayCounter = 0;
		isStopping = false;
		stopTargetTile = null;
		lastDx = lastDy = 0;
		justReversed = false;
		log.info("WASD Movement stopped");
	}

	private void movementLoop()
	{
		while (running)
		{
			try
			{
				if (!isGameFocused())
				{
					resetKeys();
				}
				else if (client.getGameState() == GameState.LOGGED_IN)
				{
					if (isMovementKeyPressed())
					{
						pendingStop = false;
						stopDelayCounter = 0;
						isStopping = false;
						stopTargetTile = null;
						wasMoving = true;
						clientThread.invokeLater(this::performMovement);
					}
					else if (isStopping && stopTargetTile != null)
					{
						clientThread.invokeLater(this::handleStopValidation);
					}
					else
					{
						lastDx = lastDy = 0;
						justReversed = false;
					}
				}
				Thread.sleep(config.clickInterval());
			}
			catch (InterruptedException e)
			{
				break;
			}
		}
	}

	private boolean isMovementKeyPressed()
	{
		return keyW || keyA || keyS || keyD;
	}

	private boolean isGameFocused()
	{
		Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (activeWindow == null)
		{
			return false;
		}
		return javax.swing.SwingUtilities.isDescendingFrom(client.getCanvas(), activeWindow);
	}

	private void resetKeys()
	{
		keyW = keyA = keyS = keyD = false;
		wasMoving = false;
		pendingStop = false;
		stopDelayCounter = 0;
		isStopping = false;
		stopTargetTile = null;
		lastDx = lastDy = 0;
		justReversed = false;
	}

	private void performMovement()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Widget minimap = getMinimapWidget();
		if (minimap == null || minimap.isHidden())
		{
			return;
		}

		Rectangle bounds = minimap.getBounds();
		if (bounds == null || bounds.width == 0 || bounds.height == 0)
		{
			return;
		}

		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;

		double dx = 0, dy = 0;
		if (keyW) dy -= 1;
		if (keyS) dy += 1;
		if (keyA) dx -= 1;
		if (keyD) dx += 1;

		if (dx == 0 && dy == 0)
		{
			lastDx = lastDy = 0;
			justReversed = false;
			return;
		}

		double len = Math.sqrt(dx * dx + dy * dy);
		dx /= len;
		dy /= len;

		boolean isReversal = (lastDx != 0 || lastDy != 0) && (dx * lastDx + dy * lastDy) < -0.5;

		if (isReversal && !justReversed)
		{
			justReversed = true;
			sendClick(centerX, centerY);
			return;
		}

		justReversed = false;
		lastDx = dx;
		lastDy = dy;

		boolean isRunning = client.getVarpValue(173) == 1;
		int radius = isRunning ? RUN_CLICK_RADIUS : WALK_CLICK_RADIUS;

		int clickX = centerX + (int) (dx * radius);
		int clickY = centerY + (int) (dy * radius);

		sendClick(clickX, clickY);
	}

	private void initiateStop()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		clickStopPosition();
		pendingStop = true;
		stopDelayCounter = 0;
	}

	private void clickStopPosition()
	{
		Widget minimap = getMinimapWidget();
		if (minimap == null || minimap.isHidden())
		{
			return;
		}

		Rectangle bounds = minimap.getBounds();
		if (bounds == null || bounds.width == 0 || bounds.height == 0)
		{
			return;
		}

		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;

		if (lastDx == 0 && lastDy == 0)
		{
			sendClick(centerX, centerY);
			return;
		}

		boolean isRunning = client.getVarpValue(173) == 1;
		int offsetRadius = isRunning ? 24 : 8;
		int clickX = centerX + (int) (lastDx * offsetRadius);
		int clickY = centerY + (int) (lastDy * offsetRadius);

		sendClick(clickX, clickY);
		lastDx = lastDy = 0;
		justReversed = false;
	}

	private void captureStopPosition()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (isMovementKeyPressed())
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		stopTargetTile = localPlayer.getWorldLocation();
		isStopping = true;
	}

	private void handleStopValidation()
	{
		if (client.getGameState() != GameState.LOGGED_IN || stopTargetTile == null)
		{
			isStopping = false;
			stopTargetTile = null;
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		WorldPoint currentTile = localPlayer.getWorldLocation();
		int distance = currentTile.distanceTo(stopTargetTile);

		if (distance <= 3)
		{
			isStopping = false;
			stopTargetTile = null;
			return;
		}

		clickMinimapCenter();
	}

	private void clickMinimapCenter()
	{
		Widget minimap = getMinimapWidget();
		if (minimap == null || minimap.isHidden())
		{
			return;
		}

		Rectangle bounds = minimap.getBounds();
		if (bounds == null || bounds.width == 0 || bounds.height == 0)
		{
			return;
		}

		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;

		sendClick(centerX, centerY);
	}

	private void sendClick(int clickX, int clickY)
	{
		long when = System.currentTimeMillis();

		MouseEvent pressed = new MouseEvent(
			client.getCanvas(),
			MouseEvent.MOUSE_PRESSED,
			when,
			MouseEvent.BUTTON1_DOWN_MASK,
			clickX,
			clickY,
			1,
			false,
			MouseEvent.BUTTON1
		);

		MouseEvent released = new MouseEvent(
			client.getCanvas(),
			MouseEvent.MOUSE_RELEASED,
			when + 10,
			0,
			clickX,
			clickY,
			1,
			false,
			MouseEvent.BUTTON1
		);

		MouseEvent clicked = new MouseEvent(
			client.getCanvas(),
			MouseEvent.MOUSE_CLICKED,
			when + 10,
			0,
			clickX,
			clickY,
			1,
			false,
			MouseEvent.BUTTON1
		);

		client.getCanvas().dispatchEvent(pressed);
		client.getCanvas().dispatchEvent(released);
		client.getCanvas().dispatchEvent(clicked);
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (client.getGameState() != GameState.LOGGED_IN || !isGameFocused())
		{
			return;
		}

		switch (e.getKeyCode())
		{
			case KeyEvent.VK_W:
				keyW = true;
				e.consume();
				break;
			case KeyEvent.VK_A:
				keyA = true;
				e.consume();
				break;
			case KeyEvent.VK_S:
				keyS = true;
				e.consume();
				break;
			case KeyEvent.VK_D:
				keyD = true;
				e.consume();
				break;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		boolean wasWASD = false;
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_W:
				keyW = false;
				wasWASD = true;
				break;
			case KeyEvent.VK_A:
				keyA = false;
				wasWASD = true;
				break;
			case KeyEvent.VK_S:
				keyS = false;
				wasWASD = true;
				break;
			case KeyEvent.VK_D:
				keyD = false;
				wasWASD = true;
				break;
		}

		if (wasWASD && wasMoving && !isMovementKeyPressed())
		{
			wasMoving = false;
			clientThread.invokeLater(this::initiateStop);
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (pendingStop)
		{
			stopDelayCounter++;
			if (stopDelayCounter >= STOP_DELAY_TICKS)
			{
				pendingStop = false;
				stopDelayCounter = 0;
				captureStopPosition();
			}
		}
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		return e;
	}

	private Widget getMinimapWidget()
	{
		Widget minimap;

		minimap = client.getWidget(RESIZABLE_MINIMAP_GROUP, RESIZABLE_MINIMAP_CHILD);
		if (minimap != null && !minimap.isHidden())
		{
			return minimap;
		}

		minimap = client.getWidget(RESIZABLE_BOTTOM_MINIMAP_GROUP, RESIZABLE_BOTTOM_MINIMAP_CHILD);
		if (minimap != null && !minimap.isHidden())
		{
			return minimap;
		}

		minimap = client.getWidget(FIXED_MINIMAP_GROUP, FIXED_MINIMAP_CHILD);
		if (minimap != null && !minimap.isHidden())
		{
			return minimap;
		}

		return null;
	}

	@Provides
	WASDMovementConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WASDMovementConfig.class);
	}
}
