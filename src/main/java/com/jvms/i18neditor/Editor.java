package com.jvms.i18neditor;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jvms.i18neditor.Resource.ResourceType;
import com.jvms.i18neditor.swing.JFileDrop;
import com.jvms.i18neditor.swing.JHtmlPane;
import com.jvms.i18neditor.swing.JScrollablePanel;
import com.jvms.i18neditor.util.Dialogs;
import com.jvms.i18neditor.util.ExtendedProperties;
import com.jvms.i18neditor.util.GithubRepoUtils;
import com.jvms.i18neditor.util.GithubRepoUtils.GithubReleaseData;
import com.jvms.i18neditor.util.MessageBundle;
import com.jvms.i18neditor.util.Resources;
import com.jvms.i18neditor.util.TranslationKeys;

/**
 * This class represents the main class of the editor.
 * 
 * @author Jacob
 */
public class Editor extends JFrame {
	private final static long serialVersionUID = 1113029729495390082L;
	
	public final static Path SETTINGS_PATH = Paths.get(System.getProperty("user.home"), ".i18n-editor");
	public final static String TITLE = "i18n-editor";
	public final static String VERSION = "1.0.0";
	public final static String GITHUB_REPO = "jcbvm/ember-i18n-editor";
	public final static int DEFAULT_WIDTH = 1024;
	public final static int DEFAULT_HEIGHT = 768;
	
	private List<Resource> resources = Lists.newLinkedList();
	private Path resourcesDir;
	private boolean dirty;
	private boolean minifyOutput;
	
	private EditorMenu editorMenu;
	private JSplitPane contentPane;
	private JLabel introText;
	private JPanel translationsPanel;
	private JScrollPane resourcesScrollPane;
	private TranslationTree translationTree;
	private TranslationField translationField;
	private JPanel resourcesPanel;
	private List<ResourceField> resourceFields = Lists.newLinkedList();
	private ExtendedProperties settings = new ExtendedProperties();
	private final ExecutorService executor = Executors.newCachedThreadPool();
	
	public Editor() {
		super();
		setupUI();
		setupFileDrop();
	}
	
	public void importResources(Path dir) {
		if (!closeCurrentSession()) {
			return;
		}
		if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
			if (resourcesDir != null) {
				reset();				
			}
			resourcesDir = dir;
		} else {
			showError(MessageBundle.get("resources.open.error.multiple"));
			return;
		}
		try {
			Files.walk(resourcesDir, 1).filter(path -> Resources.isResource(path)).forEach(path -> {
				try {
					Resource resource = Resources.read(path);
					setupResource(resource);
				} catch (Exception e) {
					e.printStackTrace();
					showError(MessageBundle.get("resources.open.error.single", path.toString()));
				}
			});
			
			Map<String,String> keys = Maps.newTreeMap();
			resources.forEach(resource -> keys.putAll(resource.getTranslations()));
			List<String> keyList = Lists.newArrayList(keys.keySet());
			translationTree.setModel(new TranslationTreeModel(keyList));
			
			updateHistory();
			updateUI();
		} catch (IOException e) {
			e.printStackTrace();
			showError(MessageBundle.get("resources.open.error.multiple"));
		}
	}
	
	public void saveResources() {
		boolean error = false;
		for (Resource resource : resources) {
			try {
				Resources.write(resource, !minifyOutput);
			} catch (Exception e) {
				error = true;
				e.printStackTrace();
				showError(MessageBundle.get("resources.write.error.single", resource.getPath().toString()));
			}
		}
		setDirty(error);
	}
	
	public void reloadResources() {
		importResources(resourcesDir);
	}
	
	public void removeSelectedTranslation() {
		TranslationTreeNode node = translationTree.getSelectedNode();
		if (node != null && !node.isRoot()) {
			TranslationTreeNode parent = (TranslationTreeNode) node.getParent();
			removeTranslationKey(node.getKey());
			translationTree.setSelectedNode(parent);
		}
	}
	
	public void renameSelectedTranslation() {
		TranslationTreeNode node = translationTree.getSelectedNode();
		if (node != null && !node.isRoot()) {
			showRenameTranslationDialog(node.getKey());
		}
	}
	
	public void duplicateSelectedTranslation() {
		TranslationTreeNode node = translationTree.getSelectedNode();
		if (node != null && !node.isRoot()) {
			showDuplicateTranslationDialog(node.getKey());
		}
	}
	
	public void addTranslationKey(String key) {
		if (resources.isEmpty()) return;
		TranslationTreeNode node = translationTree.getNodeByKey(key);
		if (node != null) {
			translationTree.setSelectedNode(node);
		} else {
			resources.forEach(resource -> resource.storeTranslation(key, ""));
			translationTree.addNodeByKey(key);			
		}
	}
	
	public void removeTranslationKey(String key) {
		if (resources.isEmpty()) return;
		resources.forEach(resource -> resource.removeTranslation(key));
		translationTree.removeNodeByKey(key);
	}
	
	public void renameTranslationKey(String key, String newKey) {
		if (resources.isEmpty() || key.equals(newKey)) return;
		resources.forEach(resource -> resource.renameTranslation(key, newKey));
		translationTree.renameNodeByKey(key, newKey);
	}
	
	public void duplicateTranslationKey(String key, String newKey) {
		if (resources.isEmpty() || key.equals(newKey)) return;
		resources.forEach(resource -> resource.duplicateTranslation(key, newKey));
		translationTree.duplicateNodeByKey(key, newKey);
	}
	
	public Path getResourcesPath() {
		return resourcesDir;
	}
	
	public boolean isDirty() {
		return dirty;
	}
	
	public void setDirty(boolean dirty) {
		this.dirty = dirty;
		updateTitle();
		editorMenu.setSaveable(dirty);
	}
	
	public boolean isMinifyOutput() {
		return minifyOutput;
	}
	
	public void setMinifyOutput(boolean minifyOutput) {
		this.minifyOutput = minifyOutput;
	}
	
	public void showImportDialog() {
		String path = null;
		if (resourcesDir != null) {
			path = resourcesDir.toString();
		}
		JFileChooser fc = new JFileChooser(path);
		fc.setDialogTitle(MessageBundle.get("dialogs.import.title"));
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		int result = fc.showOpenDialog(this);
		if (result == JFileChooser.APPROVE_OPTION) {
			importResources(Paths.get(fc.getSelectedFile().getPath()));
		} else {
			updateHistory();
			updateUI();
		}
	}
	
	public void showAddLocaleDialog(ResourceType type) {
		String locale = "";
		while (locale != null && locale.isEmpty()) {
			locale = Dialogs.showInputDialog(this,
					MessageBundle.get("dialogs.locale.add.title", type),
					MessageBundle.get("dialogs.locale.add.text"),
					JOptionPane.QUESTION_MESSAGE);
			if (locale != null) {
				locale = locale.trim();
				Path path = Paths.get(resourcesDir.toString(), locale);
				if (locale.isEmpty() || Files.isDirectory(path)) {
					showError(MessageBundle.get("dialogs.locale.add.error.invalid"));
				} else {
					try {
						Resource resource = Resources.create(type, path);
						setupResource(resource);
						updateUI();
					} catch (IOException e) {
						e.printStackTrace();
						showError(MessageBundle.get("dialogs.locale.add.error.create"));
					}
				}
			}
		}
	}
	
	public void showRenameTranslationDialog(String key) {
		String newKey = "";
		while (newKey != null && newKey.isEmpty()) {
			newKey = Dialogs.showInputDialog(this,
					MessageBundle.get("dialogs.translation.rename.title"),
					MessageBundle.get("dialogs.translation.rename.text"),
					JOptionPane.QUESTION_MESSAGE, key, true);
			if (newKey != null) {
				if (!TranslationKeys.isValid(newKey)) {
					showError(MessageBundle.get("dialogs.translation.rename.error"));
				} else {
					TranslationTreeNode newNode = translationTree.getNodeByKey(newKey);
					TranslationTreeNode oldNode = translationTree.getNodeByKey(key);
					if (newNode != null) {
						boolean isReplace = newNode.isLeaf() || oldNode.isLeaf();
						boolean confirm = Dialogs.showConfirmDialog(this, 
								MessageBundle.get("dialogs.translation.conflict.title"), 
								MessageBundle.get("dialogs.translation.conflict.text." + (isReplace ? "replace" : "merge")));
						if (confirm) {
							renameTranslationKey(key, newKey);
						}
					} else {
						renameTranslationKey(key, newKey);
					}
				}
			}
		}
	}
	
	public void showDuplicateTranslationDialog(String key) {
		String newKey = "";
		while (newKey != null && newKey.isEmpty()) {
			newKey = Dialogs.showInputDialog(this,
					MessageBundle.get("dialogs.translation.duplicate.title"),
					MessageBundle.get("dialogs.translation.duplicate.text"),
					JOptionPane.QUESTION_MESSAGE, key, true);
			if (newKey != null) {
				newKey = newKey.trim();
				if (!TranslationKeys.isValid(newKey)) {
					showError(MessageBundle.get("dialogs.translation.duplicate.error"));
				} else {
					TranslationTreeNode newNode = translationTree.getNodeByKey(newKey);
					TranslationTreeNode oldNode = translationTree.getNodeByKey(key);
					if (newNode != null) {
						boolean isReplace = newNode.isLeaf() || oldNode.isLeaf();
						boolean confirm = Dialogs.showConfirmDialog(this, 
								MessageBundle.get("dialogs.translation.conflict.title"), 
								MessageBundle.get("dialogs.translation.conflict.text." + (isReplace ? "replace" : "merge")));
						if (confirm) {
							duplicateTranslationKey(key, newKey);
						}
					} else {
						duplicateTranslationKey(key, newKey);
					}
				}
			}
		}
	}
	
	public void showAddTranslationDialog() {
		String key = "";
		String newKey = "";
		TranslationTreeNode node = translationTree.getSelectedNode();
		if (node != null && !node.isRoot()) {
			key = node.getKey() + ".";
		}
		while (newKey != null && newKey.isEmpty()) {
			newKey = Dialogs.showInputDialog(this,
					MessageBundle.get("dialogs.translation.add.title"),
					MessageBundle.get("dialogs.translation.add.text"),
					JOptionPane.QUESTION_MESSAGE, key, false);
			if (newKey != null) {
				newKey = newKey.trim();
				if (!TranslationKeys.isValid(newKey)) {
					showError(MessageBundle.get("dialogs.translation.add.error"));
				} else {
					addTranslationKey(newKey);
				}
			}
		}
	}
	
	public void showFindTranslationDialog() {
		String key = Dialogs.showInputDialog(this,
				MessageBundle.get("dialogs.translation.find.title"),
				MessageBundle.get("dialogs.translation.find.text"),
				JOptionPane.QUESTION_MESSAGE);
		if (key != null) {
			TranslationTreeNode node = translationTree.getNodeByKey(key.trim());
			if (node == null) {
				Dialogs.showWarningDialog(this, 
						MessageBundle.get("dialogs.translation.find.title"), 
						MessageBundle.get("dialogs.translation.find.error"));
			} else {
				translationTree.setSelectedNode(node);
			}
		}
	}
	
	public void showAboutDialog() {
		Dialogs.showMessageDialog(this, MessageBundle.get("dialogs.about.title", TITLE), 
				"<html><body style=\"text-align:center;width:200px;\">" +
					"<span style=\"font-weight:bold;font-size:1.2em;\">" + TITLE + "</span><br>" +
					"v" + VERSION + "<br><br>" +
					"Copyright (c) 2015 - 2017<br>" +
					"Jacob van Mourik<br>" +
					"MIT Licensed<br><br>" +
				"</body></html>");
	}
	
	public void checkForNewVersion(boolean showUpToDateFeedback) {
		executor.execute(() -> {
			GithubReleaseData data;
			String content;
			try {
				data = GithubRepoUtils.getLatestRelease(GITHUB_REPO).get(30, TimeUnit.SECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				data = null;
			}
			if (data != null && !VERSION.equals(data.getTagName())) {
				content = MessageBundle.get("dialogs.version.new", data.getTagName()) + "<br>" + 
						"<a href=\"" + data.getHtmlUrl() + "\">" + MessageBundle.get("dialogs.version.link") + "</a>";
			} else if (!showUpToDateFeedback) {
				return;
			} else {
				content = MessageBundle.get("dialogs.version.uptodate");
			}
			Font font = getFont();
			JHtmlPane pane = new JHtmlPane(this, "<html><body style=\"font-family:" + font.getFamily() + ";font-size:" + font.getSize() + "pt;text-align:center;width:200px;\">" + content + "</body></html>");
			Dialogs.showMessageDialog(this, MessageBundle.get("dialogs.version.title"), pane);			
		});
	}
	
	public boolean closeCurrentSession() {
		if (isDirty()) {
			int result = JOptionPane.showConfirmDialog(this, 
					MessageBundle.get("dialogs.save.text"), 
					MessageBundle.get("dialogs.save.title"), 
					JOptionPane.YES_NO_CANCEL_OPTION);
			if (result == JOptionPane.YES_OPTION) {
				saveResources();
			}
			return result != JOptionPane.CANCEL_OPTION;
		}
		return true;
	}
	
	public void reset() {
		translationTree.clear();
		resources.clear();
		resourceFields.clear();
		setDirty(false);
		updateUI();
	}
	
	public void launch() {
		settings.load(SETTINGS_PATH);
		
		// Restore editor settings
		minifyOutput = settings.getBooleanProperty("minify_output");
    	
		// Restore window bounds
		setPreferredSize(new Dimension(settings.getIntegerProperty("window_width", 1024), settings.getIntegerProperty("window_height", 768)));
		setLocation(settings.getIntegerProperty("window_pos_x", 0), settings.getIntegerProperty("window_pos_y", 0));
		contentPane.setDividerLocation(settings.getIntegerProperty("divider_pos", 250));
		
    	pack();
    	setVisible(true);
    	
		if (!loadResourcesFromHistory()) {
    		showImportDialog();
    	} else {
    		// Restore last expanded nodes
			List<String> expandedKeys = settings.getListProperty("last_expanded");
			List<TranslationTreeNode> expandedNodes = expandedKeys.stream()
					.map(k -> translationTree.getNodeByKey(k))
					.filter(n -> n != null)
					.collect(Collectors.toList());
			translationTree.expand(expandedNodes);
			
			// Restore last selected node
			String selectedKey = settings.getProperty("last_selected");
			TranslationTreeNode selectedNode = translationTree.getNodeByKey(selectedKey);
			if (selectedNode != null) {
				translationTree.setSelectedNode(selectedNode);
			}
    	}
		
    	checkForNewVersion(false);
	}
	
	private void setupUI() {
		setTitle(TITLE);
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new EditorWindowListener());
		
		setIconImages(Lists.newArrayList("512","256","128","64","48","32","24","20","16").stream()
				.map(size -> getResourceImage("images/icon-" + size + ".png"))
				.collect(Collectors.toList()));
		
		translationsPanel = new JPanel(new BorderLayout());
        translationTree = new TranslationTree(this);
        translationTree.addTreeSelectionListener(new TranslationTreeNodeSelectionListener());
		translationField = new TranslationField();
		translationField.addKeyListener(new TranslationFieldKeyListener());
		translationsPanel.add(new JScrollPane(translationTree));
		translationsPanel.add(translationField, BorderLayout.SOUTH);
		
        resourcesPanel = new JScrollablePanel(true, false);
        resourcesPanel.setLayout(new BoxLayout(resourcesPanel, BoxLayout.Y_AXIS));
        resourcesPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        
        resourcesScrollPane = new JScrollPane(resourcesPanel);
        resourcesScrollPane.getViewport().setOpaque(false);
        resourcesScrollPane.setBackground(resourcesPanel.getBackground());
        
		contentPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, translationsPanel, resourcesScrollPane);
		editorMenu = new EditorMenu(this, translationTree);
		
		introText = new JLabel("<html><body style=\"text-align:center; padding:15px;\">" + MessageBundle.get("core.intro.text") + "</body></html>");
		introText.setOpaque(true);
		introText.setFont(introText.getFont().deriveFont(28f));
		introText.setHorizontalTextPosition(JLabel.CENTER);
		introText.setVerticalTextPosition(JLabel.BOTTOM);
		introText.setHorizontalAlignment(JLabel.CENTER);
		introText.setVerticalAlignment(JLabel.CENTER);
		introText.setForeground(getBackground().darker());
		introText.setIcon(new ImageIcon(getResourceImage("images/icon-intro.png")));
		
		setJMenuBar(editorMenu);
	}
	
	private void setupFileDrop() {
		new JFileDrop(this, new JFileDrop.Listener() {
			@Override
			public void filesDropped(java.io.File[] files) {
				try {
					Path path = Paths.get(files[0].getCanonicalPath());
					importResources(path);
                } catch (IOException e ) {
                	e.printStackTrace();
                	showError(MessageBundle.get("resources.open.error.multiple"));
                }
            }
        });
	}
	
	private void setupResource(Resource resource) {
		resource.addListener(e -> setDirty(true));
		ResourceField field = new ResourceField(resource);
		field.addKeyListener(new ResourceFieldKeyListener());
		resources.add(resource);
		resourceFields.add(field);
	}
	
	private void updateUI() {
		TranslationTreeNode selectedNode = translationTree.getSelectedNode();
		
		resourcesPanel.removeAll();
		resourceFields.stream().sorted().forEach(field -> {
			field.setEditable(selectedNode != null && selectedNode.isEditable());
			resourcesPanel.add(Box.createVerticalStrut(5));
			resourcesPanel.add(new JLabel(field.getResource().getLocale().getDisplayName()));
			resourcesPanel.add(Box.createVerticalStrut(5));
			resourcesPanel.add(field);
			resourcesPanel.add(Box.createVerticalStrut(5));
		});
		if (!resourceFields.isEmpty()) {
			resourcesPanel.remove(0);
			resourcesPanel.remove(resourcesPanel.getComponentCount()-1);
		}
		
		Container container = getContentPane();
		if (resourcesDir != null) {
			container.add(contentPane);
			container.remove(introText);
		} else {
			container.add(introText);
			container.remove(contentPane);
		}
		
		editorMenu.setEnabled(resourcesDir != null);
		editorMenu.setEditable(!resources.isEmpty());
		translationTree.setEditable(!resources.isEmpty());
		translationField.setEditable(!resources.isEmpty());
		
		updateTitle();
		validate();
		repaint();
	}
	
	private void updateHistory() {
		List<String> recentDirs = settings.getListProperty("history");
		if (resourcesDir != null) {
			String path = resourcesDir.toString();
			recentDirs.remove(path);
			recentDirs.add(path);
			if (recentDirs.size() > 5) {
				recentDirs.remove(0);
			}
			settings.setProperty("history", recentDirs);			
		}
		editorMenu.setRecentItems(Lists.reverse(recentDirs));
	}
	
	private void updateTitle() {
		String dirtyPart = dirty ? "*" : "";
		String filePart = resourcesDir == null ? "" : resourcesDir.toString() + " - ";
		setTitle(dirtyPart + filePart + TITLE);
	}
	
	private boolean loadResourcesFromHistory() {
		List<String> dirs = settings.getListProperty("history");
    	if (!dirs.isEmpty()) {
    		String lastDir = dirs.get(dirs.size()-1);
    		Path path = Paths.get(lastDir);
    		if (Files.exists(path)) {
    			importResources(path);
    			return true;
    		}
    	}
    	return false;
	}
	
	private void showError(String message) {
		Dialogs.showErrorDialog(this, MessageBundle.get("dialogs.error.title"), message);
	}
	
	private Image getResourceImage(String path) {
		return new ImageIcon(getClass().getClassLoader().getResource(path)).getImage();
	}
	
	private void storeEditorState() {
		// Store editor settings
		settings.setProperty("minify_output", minifyOutput);
		
		// Store window bounds
		settings.setProperty("window_width", getWidth());
		settings.setProperty("window_height", getHeight());
		settings.setProperty("window_pos_x", getX());
		settings.setProperty("window_pos_y", getY());
		settings.setProperty("divider_pos", contentPane.getDividerLocation());
		
		if (!resources.isEmpty()) {
			// Store keys of expanded nodes
			List<String> expandedNodeKeys = translationTree.getExpandedNodes().stream()
					.map(n -> n.getKey())
					.collect(Collectors.toList());
			settings.setProperty("last_expanded", expandedNodeKeys);
			
			// Store key of selected node
			TranslationTreeNode selectedNode = translationTree.getSelectedNode();
			settings.setProperty("last_selected", selectedNode == null ? "" : selectedNode.getKey());
		}
		
		settings.store(SETTINGS_PATH);
	}
	
	private class TranslationTreeNodeSelectionListener implements TreeSelectionListener {
		@Override
		public void valueChanged(TreeSelectionEvent e) {
			TranslationTreeNode node = translationTree.getSelectedNode();
			
			if (node != null) {
				// Store scroll position
				int scrollValue = resourcesScrollPane.getVerticalScrollBar().getValue();
				
				// Update UI values
				String key = node.getKey();
				translationField.setValue(key);
				resourceFields.forEach(f -> {
					f.setValue(key);
					f.setEditable(node.isEditable());
				});
				
				// Restore scroll position
				SwingUtilities.invokeLater(() -> resourcesScrollPane.getVerticalScrollBar().setValue(scrollValue));
			}
		}
	}
	
	private class ResourceFieldKeyListener extends KeyAdapter {
		@Override
		public void keyReleased(KeyEvent e) {
			ResourceField field = (ResourceField) e.getSource();
			String key = translationTree.getSelectedNode().getKey();
			String value = field.getValue();
			field.getResource().storeTranslation(key, value);
		}
	}
	
	private class TranslationFieldKeyListener extends KeyAdapter {
		@Override
		public void keyReleased(KeyEvent e) {
			if (e.getKeyCode() == KeyEvent.VK_ENTER) {
				TranslationField field = (TranslationField) e.getSource();
				String key = field.getValue();
				if (TranslationKeys.isValid(key)) {
					addTranslationKey(key);						
				}
			}
		}
	}
	
	private class EditorWindowListener extends WindowAdapter {
		@Override
		public void windowClosing(WindowEvent e) {
			if (closeCurrentSession()) {
				storeEditorState();
				System.exit(0);
			}
  		}
	}
}
