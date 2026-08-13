package com.tommyknight.flinch;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ComboBoxEditor;
import javax.swing.ComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.MatteBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The whole UI. The config panel holds only the sidebar toggle, so every setting the user
 * cares about is here.
 */
class FlinchPanel extends PluginPanel
{
	private static final Color SECTION_COLOR = new Color(220, 138, 0);

	/** Wrapped text wider than the panel is clipped rather than wrapped, so it is set explicitly. */
	private static final int TEXT_WIDTH = 165;

	/** A one-character query matches thousands of entries; only the first screenful is ever read. */
	private static final int MAX_SEARCH_RESULTS = 250;

	/** Fixes the combo box cell width up front so Swing never measures all 1,500 rows. */
	private static final FlinchAnimation PROTOTYPE =
		new FlinchAnimation(-2, "Wwwwwwwwwwwwwwwwwwww", "", FlinchAnimation.Group.NONE);

	private final FlinchCatalogue catalogue;

	private FlinchPlugin plugin;

	private final JCheckBox enabledBox = new JCheckBox("Enabled");
	private final JCheckBox animationCancelBox = new JCheckBox("Animation cancel");
	private final JLabel loggedOutLabel = new JLabel("Log in to preview animations.");
	private final JPanel loggedOutRow;

	private final Map<FlinchTrigger, JComboBox<FlinchAnimation>> combos = new EnumMap<>(FlinchTrigger.class);

	/** Set while the panel writes its own widgets, so listeners don't echo back into config. */
	private boolean loading;

	@Inject
	FlinchPanel(FlinchCatalogue catalogue)
	{
		super(false);
		this.catalogue = catalogue;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// The sidebar reserves room for a scroll bar on the right whether or not one is showing,
		// so the right inset is larger than the left to keep the gap looking even.
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 24));

		content.add(row(title(), 0));
		content.add(row(enabledBox, 10));
		content.add(row(animationCancelBox, 6));
		content.add(row(hint("Holds the animation against your own attack swings instead of "
			+ "letting them cut it short. Moving still cancels it."), 0));

		loggedOutLabel.setFont(FontManager.getRunescapeSmallFont());
		loggedOutLabel.setForeground(SECTION_COLOR);
		loggedOutRow = row(loggedOutLabel, 8);
		content.add(loggedOutRow);

		FlinchTrigger.Section current = null;
		for (FlinchTrigger trigger : FlinchTrigger.values())
		{
			if (trigger.getSection() != current)
			{
				current = trigger.getSection();
				content.add(row(sectionHeader(current.displayName), 14));
			}

			content.add(row(triggerRow(trigger), 8));
		}

		final JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		enabledBox.addActionListener(e ->
		{
			if (!loading && plugin != null)
			{
				plugin.setEnabled(enabledBox.isSelected());
			}
		});

		animationCancelBox.addActionListener(e ->
		{
			if (!loading && plugin != null)
			{
				plugin.setAnimationCancel(animationCancelBox.isSelected());
			}
		});
	}

	void init(FlinchPlugin plugin)
	{
		this.plugin = plugin;
		reload();
	}

	@Override
	public void onActivate()
	{
		super.onActivate();
		reload();
	}

	/** Pulls every widget's state back from config. */
	void reload()
	{
		if (plugin == null)
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			loading = true;
			try
			{
				enabledBox.setSelected(plugin.isEnabled());
				animationCancelBox.setSelected(plugin.isAnimationCancel());

				final Map<FlinchTrigger, FlinchAnimation> animations = plugin.getAllAnimations();
				for (Map.Entry<FlinchTrigger, JComboBox<FlinchAnimation>> entry : combos.entrySet())
				{
					final JComboBox<FlinchAnimation> combo = entry.getValue();
					final FlinchAnimation animation = animations.get(entry.getKey());
					((FilterModel) combo.getModel()).clearFilter();
					combo.setSelectedItem(animation);
					combo.setToolTipText(describe(animation));
				}
			}
			finally
			{
				loading = false;
			}
		});
	}

	void setLoggedIn(boolean loggedIn)
	{
		SwingUtilities.invokeLater(() ->
		{
			loggedOutRow.setVisible(!loggedIn);
			revalidate();
			repaint();
		});
	}

	// --- Search ---

	/** Whether the user is mid-query, during which the combo must not rewrite the editor. */
	private static final class SearchState
	{
		private boolean typing;
	}

	/** Drops any query, restores the full list, and shows what is actually saved. */
	private void endSearch(JComboBox<FlinchAnimation> combo, FilterModel model,
		FlinchTrigger trigger, SearchState search)
	{
		search.typing = false;

		if (plugin == null)
		{
			return;
		}

		loading = true;
		try
		{
			model.clearFilter();

			// Whatever is on screen wins, and is written back on the way out. Reading the
			// stored value here instead would silently roll a fresh choice back to the old one
			// if its change event had not been recorded for any reason.
			final Object selected = combo.getSelectedItem();
			final FlinchAnimation animation = selected instanceof FlinchAnimation
				? (FlinchAnimation) selected
				: plugin.getAllAnimations().get(trigger);

			plugin.setAnimationId(trigger, animation.getId());
			combo.setSelectedItem(animation);
			// Explicit, because setSelectedItem is a no-op when the selection has not changed
			// and the editor would otherwise keep showing the abandoned query.
			combo.getEditor().setItem(animation);
			combo.setToolTipText(describe(animation));
		}
		finally
		{
			loading = false;
		}
	}

	/**
	 * A combo box model that can narrow itself to a subset without being replaced.
	 *
	 * Swapping in a whole new model resets the combo's editor, which fires document events,
	 * which filter again, an infinite loop. Filtering in place leaves the editor alone and
	 * only tells the popup its contents changed.
	 */
	private static final class FilterModel extends AbstractListModel<FlinchAnimation>
		implements ComboBoxModel<FlinchAnimation>
	{
		private final FlinchCatalogue catalogue;
		private List<FlinchAnimation> visible;
		private Object selected;

		FilterModel(FlinchCatalogue catalogue)
		{
			this.catalogue = catalogue;
			this.visible = catalogue.getAllAnimations();
		}

		boolean setFilter(String query)
		{
			final List<FlinchAnimation> next = catalogue.search(query, MAX_SEARCH_RESULTS);
			final int previousSize = visible.size();
			visible = next;
			fireContentsChanged(this, 0, Math.max(previousSize, visible.size()));
			return !visible.isEmpty();
		}

		void clearFilter()
		{
			setFilter("");
		}

		@Override
		public int getSize()
		{
			return visible.size();
		}

		@Override
		public FlinchAnimation getElementAt(int index)
		{
			return visible.get(index);
		}

		@Override
		public void setSelectedItem(Object item)
		{
			if (selected == null ? item == null : selected.equals(item))
			{
				return;
			}
			selected = item;
			// Same signal DefaultComboBoxModel sends; this is what raises the combo's action event.
			fireContentsChanged(this, -1, -1);
		}

		@Override
		public Object getSelectedItem()
		{
			return selected;
		}
	}

	// --- Construction helpers ---

	private static String describe(FlinchAnimation animation)
	{
		if (animation == null || animation.getId() == FlinchAnimation.NO_ANIMATION)
		{
			return "No animation";
		}
		return animation.getGamevalName() + " (" + animation.getId() + ")";
	}

	private static JLabel title()
	{
		final JLabel label = new JLabel("Flinch");
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		return label;
	}

	private static JLabel sectionHeader(String text)
	{
		final JLabel label = new JLabel(text.toUpperCase());
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(SECTION_COLOR);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		label.setBorder(BorderFactory.createCompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 3, 0)));
		return label;
	}

	private static JLabel hint(String text)
	{
		// JLabel does not wrap; HTML with an explicit width is the standard Swing workaround.
		final JLabel label = new JLabel("<html><body style='width:" + TEXT_WIDTH + "px'>" + text + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		return label;
	}

	private JPanel triggerRow(FlinchTrigger trigger)
	{
		final JLabel label = new JLabel(trigger.getDisplayName());
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		label.setToolTipText(trigger.getTooltip());
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));

		// Editable so the entry can be typed into to filter. Arrow keys still move the
		// selection, which is how previewing by scrubbing works.
		final FilterModel model = new FilterModel(catalogue);
		final JComboBox<FlinchAnimation> combo = new JComboBox<>(model);
		combo.setEditable(true);
		combo.setMaximumRowCount(12);
		combo.setPrototypeDisplayValue(PROTOTYPE);
		combos.put(trigger, combo);

		final JTextField editor = (JTextField) combo.getEditor().getEditorComponent();
		editor.setToolTipText("Type to search, or use the up and down arrow keys to preview");

		// While the user is typing, the combo must stop writing the selected animation's name
		// back into the editor. Otherwise each keystroke restores the old text and appends to
		// it, and deleting is impossible because the name reappears. The look and feel's own
		// editor component is kept so the field still looks native; only setItem is gated.
		final SearchState search = new SearchState();
		final ComboBoxEditor delegate = combo.getEditor();
		combo.setEditor(new ComboBoxEditor()
		{
			@Override
			public Component getEditorComponent()
			{
				return delegate.getEditorComponent();
			}

			@Override
			public void setItem(Object item)
			{
				if (!search.typing)
				{
					delegate.setItem(item);
				}
			}

			@Override
			public Object getItem()
			{
				return delegate.getItem();
			}

			@Override
			public void selectAll()
			{
				delegate.selectAll();
			}

			@Override
			public void addActionListener(ActionListener l)
			{
				delegate.addActionListener(l);
			}

			@Override
			public void removeActionListener(ActionListener l)
			{
				delegate.removeActionListener(l);
			}
		});

		combo.addActionListener(e ->
		{
			if (loading || plugin == null)
			{
				return;
			}

			final Object selected = combo.getSelectedItem();
			if (!(selected instanceof FlinchAnimation))
			{
				return;
			}

			final FlinchAnimation animation = (FlinchAnimation) selected;
			plugin.setAnimationId(trigger, animation.getId());
			combo.setToolTipText(describe(animation));

			// An open popup means the user is still arrowing through results; a closed one
			// means they committed, so the search ends and the editor shows the choice.
			if (!combo.isPopupVisible())
			{
				search.typing = false;
				combo.getEditor().setItem(animation);
			}

			// Play on every change so arrowing through the list previews as it goes.
			plugin.play(animation.getId());
		});

		// Filtering hangs off real key presses rather than the editor's document, so a
		// programmatic selection, which also rewrites the editor text, never narrows the
		// list. Navigation keys are ignored for the same reason: arrowing through results
		// must not re-filter to whatever the highlighted entry happens to be called.
		editor.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				switch (e.getKeyCode())
				{
					case KeyEvent.VK_UP:
					case KeyEvent.VK_DOWN:
					case KeyEvent.VK_LEFT:
					case KeyEvent.VK_RIGHT:
					case KeyEvent.VK_PAGE_UP:
					case KeyEvent.VK_PAGE_DOWN:
					case KeyEvent.VK_TAB:
						return;

					case KeyEvent.VK_ENTER:
					case KeyEvent.VK_ESCAPE:
						// Arrowing onto an entry already saved it, so there is nothing pending
						// to commit or discard, so both keys just end the search.
						endSearch(combo, model, trigger, search);
						return;

					default:
						break;
				}

				search.typing = true;
				final boolean anyMatches = model.setFilter(editor.getText());

				// Showing a popup throws if the panel is not on screen yet.
				if (combo.isShowing())
				{
					combo.setPopupVisible(anyMatches);
				}
			}
		});

		editor.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				// Select the existing text so the first keystroke replaces it rather than
				// appending to the name of whatever is already chosen.
				editor.selectAll();
			}
		});

		editor.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				// Leaving a half-typed query behind would strand the dropdown on a filtered
				// list, so the full one is put back with whatever is actually saved selected.
				endSearch(combo, model, trigger, search);
			}
		});

		final JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(label, BorderLayout.NORTH);
		wrapper.add(combo, BorderLayout.CENTER);
		return wrapper;
	}

	/**
	 * Wraps a component for the vertical stack.
	 *
	 * BoxLayout positions children by their alignmentX, so a stack that mixes defaults ends up
	 * visibly ragged, so every row has to declare the same alignment. It also honours maximum
	 * size, so an uncapped row stretches to fill spare vertical space. Gaps are borders rather
	 * than strut components for the same alignment reason.
	 */
	private static JPanel row(Component component, int gapTop)
	{
		final JPanel panel = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setBorder(BorderFactory.createEmptyBorder(gapTop, 0, 0, 0));
		panel.add(component, BorderLayout.CENTER);
		return panel;
	}
}
