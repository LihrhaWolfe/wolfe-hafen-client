/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import haven.QualityList.SingleType;
import haven.resutil.Curiosity;
import me.ender.Reflect;

import java.awt.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.util.List;

import haven.ItemInfo.AttrCache;
import rx.functions.Action0;
import rx.functions.Action3;

import static haven.Inventory.sqsz;

public class WItem extends Widget implements DTarget2 {
    public static final Resource missing = Resource.local().loadwait("gfx/invobjs/missing");
    public static final Coord TEXT_PADD_TOP = new Coord(0, -3), TEXT_PADD_BOT = new Coord(0, 2);
    public static final Color DURABILITY_COLOR = new Color(214, 253, 255);
    public static final Color ARMOR_COLOR = new Color(255, 227, 191);
    public static final Color MATCH_COLOR = new Color(255, 32, 255, 255);
    public Coord lsz = new Coord(1, 1);
    public final GItem item;
    public Contents contents;
    public Window contentswnd;
    private boolean hovering = false;
    private Resource cspr = null;
    private Message csdt = Message.nil;
    private final List<Action3<WItem, Coord, Integer>> rClickListeners = new LinkedList<>();
    private boolean checkDrop = false;
    private final CFG.Observer<Boolean> resetTooltip = cfg -> longtip = null;
    private final Action0 itemMatched = this::itemMatched;
    
    public WItem(GItem item) {
	super(sqsz);
	contains =  item.contains;
	name =  item.name;
	quantity =  item.quantity;
	itemq =  item.itemq;
	this.item = item;
	this.item.onBound(widget -> this.bound());
	CFG.REAL_TIME_CURIO.observe(resetTooltip);
	CFG.SHOW_CURIO_LPH.observe(resetTooltip);
	item.addMatchListener(itemMatched);
    }

    public void drawmain(GOut g, GSprite spr) {
	spr.draw(g);
    }

    public class ItemTip implements Indir<Tex>, ItemInfo.InfoTip {
	private final List<ItemInfo> info;
	private final TexI tex;

	public ItemTip(List<ItemInfo> info, BufferedImage img) {
	    this.info = info;
	    if(img == null)
		throw(new Loading());
	    tex = new TexI(img);
	}

	public GItem item() {return(item);}
	public List<ItemInfo> info() {return(info);}
	public Tex get() {return(tex);}
    }

    public class ShortTip extends ItemTip {
	public ShortTip(List<ItemInfo> info) {super(info, ItemInfo.shorttip(info));}
    }

    public class LongTip extends ItemTip {
	public LongTip(List<ItemInfo> info) {super(info, ItemInfo.longtip(info));}
    }

    private double hoverstart;
    private ItemTip shorttip = null, longtip = null;
    private List<ItemInfo> ttinfo = null;
    public Object tooltip(Coord c, Widget prev) {
	double now = Utils.rtime();
	if(prev == this) {
	} else if(prev instanceof WItem) {
	    double ps = ((WItem)prev).hoverstart;
	    if(now - ps < 1.0)
		hoverstart = now;
	    else
		hoverstart = ps;
	} else {
	    hoverstart = now;
	}
	try {
	    List<ItemInfo> info = item.info();
	    if(info.size() < 1)
		return(null);
	    if(info != ttinfo) {
		shorttip = longtip = null;
		ttinfo = info;
	    }
	    if(now - hoverstart < 1.0) {
		if(shorttip == null)
		    shorttip = new ShortTip(info);
		return(shorttip);
	    } else {
		if(longtip == null)
		    longtip = new LongTip(info);
		return(longtip);
	    }
	} catch(Loading e) {
	    return("...");
	}
    }

    private List<ItemInfo> info() {return(item.info());}
    public final AttrCache<Color> olcol = new AttrCache<>(this::info, info -> {
	    ArrayList<GItem.ColorInfo> ols = new ArrayList<>();
	    for(ItemInfo inf : info) {
		if(inf instanceof GItem.ColorInfo)
		    ols.add((GItem.ColorInfo)inf);
	    }
	    if(ols.size() == 0)
		return(() -> null);
	    if(ols.size() == 1)
		return(ols.get(0)::olcol);
	    ols.trimToSize();
	    return(() -> {
		    Color ret = null;
		    for(GItem.ColorInfo ci : ols) {
			Color c = ci.olcol();
			if(c != null)
			    ret = (ret == null) ? c : Utils.preblend(ret, c);
		    }
		    return(ret);
		});
	});
    public final AttrCache<GItem.InfoOverlay<?>[]> itemols = new AttrCache<>(this::info, info -> {
	    ArrayList<GItem.InfoOverlay<?>> buf = new ArrayList<>();
	    for(ItemInfo inf : info) {
		if(inf instanceof GItem.OverlayInfo)
		    buf.add(GItem.InfoOverlay.create((GItem.OverlayInfo<?>)inf));
	    }
	    GItem.InfoOverlay<?>[] ret = buf.toArray(new GItem.InfoOverlay<?>[0]);
	    return(() -> ret);
	});
    public final AttrCache<Double> itemmeter = new AttrCache<Double>(this::info, AttrCache.map1(GItem.MeterInfo.class, minf -> minf::meter));
    
    public final AttrCache<ItemInfo.Contents.Content> contains;
    
    public final AttrCache<QualityList> itemq;
    
    public final AttrCache<Pair<String, String>> study = new AttrCache<>(this::info, AttrCache.map1(Curiosity.class, curio -> curio::remainingTip));
    
    public final AttrCache<Tex> heurnum = new AttrCache<Tex>(this::info, AttrCache.cache(info -> {
	String num = ItemInfo.getCount(info);
	if(num == null) return null;
	return Text.renderstroked(num, Color.WHITE, Color.BLACK).tex();
    }));
    
    public final AttrCache<Tex> durability = new AttrCache<Tex>(this::info, AttrCache.cache(info -> {
	Pair<Integer, Integer> wear = ItemInfo.getWear(info);
	if(wear == null) return (null);
	return Text.renderstroked(String.valueOf(wear.b - wear.a), DURABILITY_COLOR, Color.BLACK).tex();
    })) {
	@Override
	public Tex get() {
	    return CFG.SHOW_ITEM_DURABILITY.get() ? super.get() : null;
	}
    };
    
    public final AttrCache<Pair<Double, Color>> wear = new AttrCache<>(this::info, AttrCache.cache(info->{
	Pair<Integer, Integer> wear = ItemInfo.getWear(info);
	if(wear == null) return (null);
	double bar = (float) (wear.b - wear.a) / wear.b;
	return new Pair<>(bar, Utils.blendcol(bar, Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN));
    }));
    
    public double meter() {
	Double meter = (item.meter > 0) ? (Double) (item.meter / 100.0) : itemmeter.get();
	return meter == null ? 0 : meter;
    }
    
    public final AttrCache<Tex> armor = new AttrCache<Tex>(this::info, AttrCache.cache(info -> {
	Pair<Integer, Integer> armor = ItemInfo.getArmor(info);
	if(armor == null) return (null);
	return Text.renderstroked(String.format("%d/%d", armor.a, armor.b), ARMOR_COLOR, Color.BLACK).tex();
    })) {
	@Override
	public Tex get() {
	    return CFG.SHOW_ITEM_ARMOR.get() ? super.get() : null;
	}
    };
    
    public final AttrCache<List<ItemInfo>> gilding = new AttrCache<List<ItemInfo>>(this::info, AttrCache.cache(info -> ItemInfo.findall("Slotted", info)));
    
    public final AttrCache<List<ItemInfo>> slots = new AttrCache<List<ItemInfo>>(this::info, AttrCache.cache(info -> ItemInfo.findall("ISlots", info)));

    public final AttrCache<Boolean> gildable = new AttrCache<Boolean>(this::info, AttrCache.cache(info -> {
	List<ItemInfo> slots = ItemInfo.findall("ISlots", info);
	for(ItemInfo slot : slots) {
	    if(Reflect.getFieldValueInt(slot, "left") > 0) {
		return true;
	    }
	}
	return false;
    }));
    
    public final AttrCache<String> name;
    
    public final AttrCache<Float> quantity;
    
    public final AttrCache<Curiosity> curio = new AttrCache<>(this::info, AttrCache.cache(info -> ItemInfo.find(Curiosity.class, info)), null);

    private Widget contparent() {
	/* XXX: This is a bit weird, but I'm not sure what the alternative is... */
	Widget cont = getparent(GameUI.class);
	return((cont == null) ? cont = ui.root : cont);
    }

    private GSprite lspr = null;
    private Widget lcont = null;
    public void tick(double dt) {
	/* XXX: This is ugly and there should be a better way to
	 * ensure the resizing happens as it should, but I can't think
	 * of one yet. */
	GSprite spr = item.spr();
	if((spr != null) && (spr != lspr)) {
	    Coord sz = new Coord(spr.sz());
	    lsz = sz.div(sqsz);
	    if((sz.x % sqsz.x) != 0) {
		sz.x = sqsz.x * ((sz.x / sqsz.x) + 1);
		lsz.x += 1;
	    }
	    if((sz.y % sqsz.y) != 0) {
		sz.y = sqsz.y * ((sz.y / sqsz.y) + 1);
		lsz.y += 1;
	    }
	    resize(sz);
	    lspr = spr;
	}
	if(lcont != item.contents) {
	    if((item.contents != null) && (item.contentsid != null) && (contents == null) && (contentswnd == null)) {
		Coord c = Utils.getprefc(String.format("cont-wndc/%s", item.contentsid), null);
		if(c != null) {
		    item.contents.unlink();
		    contentswnd = contparent().add(new ContentsWindow(this, item.contents), c);
		}
	    }
	    lcont = item.contents;
	}
	if(hovering) {
	    if(contents == null) {
		if((item.contents != null) && (contentswnd == null)) {
		    Widget cont = contparent();
		    ckparent: for(Widget prev : cont.children()) {
			if(prev instanceof Contents) {
			    for(Widget p = parent; p != null; p = p.parent) {
				if(p == prev)
				    break ckparent;
				if(p instanceof Contents)
				    break;
			    }
			    return;
			}
		    }
		    item.contents.unlink();
		    contents = cont.add(new Contents(this, item.contents), parentpos(cont, sz.sub(5, 5).sub(Contents.hovermarg)));
		}
	    }
	} else {
	    if((contents != null) && !contents.hovering && !contents.hasmore()) {
		contents.reqdestroy();
		contents = null;
	    }
	}
	hovering = false;
	checkDrop();
    }

    public void draw(GOut g) {
	GSprite spr = item.spr();
	if(spr != null) {
	    Coord sz = spr.sz();
	    g.defstate();
	    if(olcol.get() != null)
		g.usestate(new ColorMask(olcol.get()));
	    if(item.matches) {
		g.chcolor(MATCH_COLOR);
		g.rect(Coord.z, sz);
		g.chcolor();
	    }
	    drawmain(g, spr);
	    g.defstate();
	    GItem.InfoOverlay<?>[] ols = itemols.get();
	    if(ols != null) {
		for(GItem.InfoOverlay<?> ol : ols)
		    ol.draw(g);
	    }
	    drawbars(g, sz);
	    drawnum(g, sz);
	    drawmeter(g, sz);
	    drawq(g);
	} else {
	    g.image(missing.layer(Resource.imgc).tex(), Coord.z, sz);
	}
    }

    private void drawmeter(GOut g, Coord sz) {
	double meter = meter();
	if(meter > 0) {
	    Tex studyTime = getStudyTime();
	    if(studyTime == null && CFG.PROGRESS_NUMBER.get()) {
		Tex tex = Text.renderstroked(String.format("%d%%", Math.round(100 * meter))).tex();
		g.aimage(tex, sz.div(2), 0.5, 0.5);
		tex.dispose();
	    } else {
		g.chcolor(255, 255, 255, 64);
		Coord half = sz.div(2);
		g.prect(half, half.inv(), half, meter * Math.PI * 2);
		g.chcolor();
	    }
	    
	    if(studyTime != null) {
		g.chcolor(8, 8, 8, 80);
		int h = studyTime.sz().y + TEXT_PADD_BOT.y;
		boolean swap = CFG.SWAP_NUM_AND_Q.get();
		g.frect(new Coord(0, swap ? 0 : sz.y - h), new Coord(sz.x, h));
		g.chcolor();
		g.aimage(studyTime, new Coord(sz.x / 2, swap ? 0 : sz.y), 0.5, swap ? 0 : 1);
	    }
	}
    }
    
    private String cachedStudyValue = null;
    private String cachedTipValue = null;
    private Tex cachedStudyTex = null;
    
    private Tex getStudyTime() {
	Pair<String, String> data = study.get();
	String value = data == null ? null : data.a;
	String tip = data == null ? null : data.b;
	if(!Objects.equals(tip, cachedTipValue)) {
	    cachedTipValue = tip;
	    longtip = null;
	}
	if(value != null) {
	    if(!Objects.equals(value, cachedStudyValue)) {
		if(cachedStudyTex != null) {
		    cachedStudyTex.dispose();
		    cachedStudyTex = null;
		}
	    }
	    
	    if(cachedStudyTex == null) {
		cachedStudyValue = value;
		cachedStudyTex = Text.renderstroked(value).tex();
	    }
	    return cachedStudyTex;
	}
	return null;
    }
    
    private void drawbars(GOut g, Coord sz) {
	if(CFG.SHOW_ITEM_WEAR_BAR.get()) {
	    Pair<Double, Color> wear = this.wear.get();
	    if(wear != null) {
		g.chcolor(wear.b);
		int h = (int) (sz.y * wear.a);
		g.frect(new Coord(0, sz.y - h), new Coord(4, h));
		g.chcolor();
	    }
	}
    }

    private void drawnum(GOut g, Coord sz) {
	Tex tex;
	if(item.num >= 0) {
	    tex = Text.render(Integer.toString(item.num)).tex();
	} else {
	    tex = chainattr(/*itemnum, */heurnum, armor, durability);
	}
 
	if(tex != null) {
	    if(CFG.SWAP_NUM_AND_Q.get()) {
		g.aimage(tex, TEXT_PADD_TOP.add(sz.x, 0),1 , 0);
	    } else {
		g.aimage(tex, TEXT_PADD_BOT.add(sz), 1, 1);
	    }
	}
    }

    @SafeVarargs //actually, method just assumes you'll feed it correctly typed var args
    private static Tex chainattr(AttrCache<Tex> ...attrs){
	for(AttrCache<Tex> attr : attrs){
	    Tex tex = attr.get();
	    if(tex != null){
		return tex;
	    }
	}
	return null;
    }

    private void drawq(GOut g) {
	QualityList quality = itemq.get();
	if(quality != null && !quality.isEmpty()) {
	    Tex tex = null;
	    SingleType qtype = getQualityType();
	    if(qtype != null) {
		QualityList.Quality single = quality.single(qtype);
		if(single != null) {
		    tex = single.tex();
		}
	    }

	    if(tex != null) {
		if(CFG.SWAP_NUM_AND_Q.get()) {
		    g.aimage(tex, TEXT_PADD_BOT.add(sz), 1, 1);
		} else {
		    g.aimage(tex, TEXT_PADD_TOP.add(sz.x, 0), 1, 0);
		}
	    }
	}
    }

    private SingleType getQualityType() {
	return CFG.Q_SHOW_SINGLE.get() ? SingleType.Quality : null;
    }

    public boolean mousedown(Coord c, int btn) {
	if(checkXfer(btn)) {
	    return true;
	} else if(btn == 1) {
	    item.wdgmsg("take", c);
	    return true;
	} else if(btn == 3) {
	    synchronized (rClickListeners) {
		if(rClickListeners.isEmpty()) {
		    FlowerMenu.lastItem(this);
		    item.wdgmsg("iact", c, ui.modflags());
		} else {
		    rClickListeners.forEach(action -> action.call(this, c, ui.modflags()));
		}
	    }
	    return(true);
	}
	return(false);
    }
    
    public void onRClick(Action3<WItem, Coord, Integer> action) {
	synchronized (rClickListeners) {
	    rClickListeners.add(action);
	}
    }
    
    public void take() {
	item.wdgmsg("take", sz.div(2), 0);
    }
    
    public void rclick() {
	rclick(Coord.z, 0);
    }
    
    
    public void rclick(Coord c, int flags) {
        FlowerMenu.lastGob(null);
	item.wdgmsg("iact", c, flags);
    }
    
    public boolean is(String what) {
	return item.is(what);
    }

    private boolean checkXfer(int button) {
	boolean inv = parent instanceof Inventory;
	if(ui.modshift) {
	    if(ui.modmeta) {
		if(inv) {
		    wdgmsg("transfer-same", item, button == 3);
		    return true;
		}
	    } else if(button == 1) {
		item.wdgmsg("transfer", c);
		return true;
	    }
	} else if(ui.modctrl) {
	    if(ui.modmeta) {
		if(inv) {
		    wdgmsg("drop-same", item, button == 3);
		    return true;
		}
	    } else if(button == 1) {
		item.wdgmsg("drop", c);
		return true;
	    }
	}
	return false;
    }
    
    @Override
    public void dispose() {
	synchronized (rClickListeners) {rClickListeners.clear();}
	CFG.SHOW_CURIO_LPH.unobserve(resetTooltip);
	CFG.REAL_TIME_CURIO.unobserve(resetTooltip);
	item.remMatchListener(itemMatched);
	super.dispose();
    }
    
    private void itemMatched() {
        Inventory inv = getparent(Inventory.class);
	if(inv != null) {inv.itemsChanged();}
    }
    
    public boolean drop(WItem target, Coord cc, Coord ul) {
	return(false);
    }

    public boolean iteminteract(WItem target, Coord cc, Coord ul) {
	if(!GildingWnd.processGilding(ui,this, target)) {
	    item.wdgmsg("itemact", ui.modflags());
	}
	return(true);
    }

    public boolean mousehover(Coord c) {
	if(item.contents != null) {
	    hovering = true;
	    return(true);
	}
	return(super.mousehover(c));
    }

    public void destroy() {
	if(contents != null) {
	    contents.reqdestroy();
	    contents = null;
	}
	if(contentswnd != null) {
	    contentswnd.reqdestroy();
	    contentswnd = null;
	}
	super.destroy();
    }

    public static class Contents extends Widget {
	public static final Coord hovermarg = UI.scale(8, 8);
	public static final Tex bg = Window.bg;
	public static final IBox obox = Window.wbox;
	public final WItem cont;
	public final Widget inv;
	private boolean invdest, hovering;
	private UI.Grab dm = null;
	private Coord doff;

	public Contents(WItem cont, Widget inv) {
	    z(90);
	    this.cont = cont;
	    /* XXX? This whole movement of the inv widget between
	     * various parents is kind of weird, but it's not
	     * obviously incorrect either. A proxy widget was tried,
	     * but that was even worse, due to rootpos and similar
	     * things being unavoidable wrong. */
	    this.inv = add(inv, hovermarg.add(obox.ctloff()));
	    this.tick(0);
	}

	public void draw(GOut g) {
	    Coord bgc = new Coord();
	    Coord ctl = hovermarg.add(obox.btloff());
	    Coord cbr = sz.sub(obox.cisz()).add(ctl);
	    for(bgc.y = ctl.y; bgc.y < cbr.y; bgc.y += bg.sz().y) {
		for(bgc.x = ctl.x; bgc.x < cbr.x; bgc.x += bg.sz().x)
		    g.image(bg, bgc, ctl, cbr);
	    }
	    obox.draw(g, hovermarg, sz.sub(hovermarg));
	    super.draw(g);
	}

	public void tick(double dt) {
	    super.tick(dt);
	    resize(inv.c.add(inv.sz).add(obox.btloff()));
	    hovering = false;
	}

	public void destroy() {
	    if(!invdest) {
		inv.unlink();
		cont.item.add(inv);
	    }
	    super.destroy();
	}

	public boolean hasmore() {
	    for(WItem item : children(WItem.class)) {
		if(item.contents != null)
		    return(true);
	    }
	    return(false);
	}

	public void cdestroy(Widget w) {
	    super.cdestroy(w);
	    if(w == inv) {
		cont.item.cdestroy(w);
		invdest = true;
		this.destroy();
		cont.contents = null;
	    }
	}

	public boolean mousedown(Coord c, int btn) {
	    if(super.mousedown(c, btn))
		return(true);
	    if(btn == 1) {
		dm = ui.grabmouse(this);
		doff = c;
		return(true);
	    }
	    return(false);
	}

	public boolean mouseup(Coord c, int btn) {
	    if((dm != null) && (btn == 1)) {
		dm.remove();
		dm = null;
		return(true);
	    }
	    return(super.mouseup(c, btn));
	}

	public void mousemove(Coord c) {
	    if(dm != null) {
		if(c.dist(doff) > 10) {
		    dm.remove();
		    dm = null;
		    Coord off = inv.c;
		    inv.unlink();
		    ContentsWindow wnd = new ContentsWindow(cont, inv);
		    off = off.sub(wnd.xlate(wnd.inv.c, true));
		    cont.contentswnd = parent.add(wnd, this.c.add(off));
		    wnd.drag(doff.sub(off));
		    invdest = true;
		    destroy();
		    cont.contents = null;
		}
	    } else {
		super.mousemove(c);
	    }
	}

	public boolean mousehover(Coord c) {
	    super.mousehover(c);
	    hovering = true;
	    return(true);
	}
    }
    
    public void tryDrop() {
        checkDrop = true;
    }
    
    private void checkDrop() {
	if(checkDrop) {
	    String name = this.name.get(null);
	    if(name != null) {
		checkDrop = false;
		if((!item.matches || !CFG.AUTO_DROP_RESPECT_FILTER.get()) && ItemAutoDrop.needDrop(name)) {
		    item.drop();
		}
	    }
	}
    }

    public static class ContentsWindow extends Window {
	public final WItem cont;
	public final Widget inv;
	private boolean invdest;
	private Coord psz = null;
	private Object id;

	public ContentsWindow(WItem cont, Widget inv) {
	    super(Coord.z, cont.item.contentsnm);
	    this.cont = cont;
	    this.inv = add(inv, Coord.z);
	    this.id = cont.item.contentsid;
	    this.tick(0);
	}

	private Coord lc = null;
	public void tick(double dt) {
	    if(cont.item.contents != inv) {
		destroy();
		cont.contentswnd = null;
		return;
	    }
	    super.tick(dt);
	    if(!Utils.eq(inv.sz, psz))
		resize(inv.c.add(psz = inv.sz));
	    if(!Utils.eq(lc, this.c) && (cont.item.contentsid != null))
		Utils.setprefc(String.format("cont-wndc/%s", cont.item.contentsid), lc = this.c);
	}

	public void wdgmsg(Widget sender, String msg, Object... args) {
	    if((sender == this) && (msg == "close")) {
		reqdestroy();
		cont.contentswnd = null;
		if(cont.item.contentsid != null)
		    Utils.setprefc(String.format("cont-wndc/%s", cont.item.contentsid), null);
	    } else {
		super.wdgmsg(sender, msg, args);
	    }
	}

	public void destroy() {
	    if(!invdest) {
		inv.unlink();
		cont.item.add(inv);
	    }
	    super.destroy();
	}

	public void cdestroy(Widget w) {
	    super.cdestroy(w);
	    if(w == inv) {
		cont.item.cdestroy(w);
		invdest = true;
		this.destroy();
		cont.contentswnd = null;
	    }
	}
    }
}
