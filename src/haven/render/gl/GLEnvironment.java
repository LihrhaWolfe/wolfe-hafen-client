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

package haven.render.gl;

import java.util.*;
import java.util.function.*;
import java.nio.ByteBuffer;
import javax.media.opengl.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.DataBuffer.Usage.*;

public class GLEnvironment implements Environment {
    public final GLContext ctx;
    final Object drawmon = new Object();
    final Object prepmon = new Object();
    final Collection<GLObject> disposed = new LinkedList<>();
    final List<GLQuery> queries = new LinkedList<>(); // Synchronized on drawmon
    final Queue<GLRender> submitted = new LinkedList<>();
    Area wnd;
    private GLRender prep = null;
    private Applier curstate = new Applier(this);
    private boolean invalid = false;

    static enum MemStats {
	INDICES, VERTICES, TEXTURES, VAOS, FBOS
    }
    final int[] stats_obj = new int[MemStats.values().length];
    final long[] stats_mem = new long[MemStats.values().length];

    public GLEnvironment(GL2 initgl, GLContext ctx, Area wnd) {
	this.ctx = ctx;
	this.wnd = wnd;
	initialize(initgl);
    }

    private void initialize(GL2 gl) {
	gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
	gl.glEnable(GL2.GL_POINT_SPRITE);
    }

    public GLRender render() {
	GLRender ret = new GLRender(this);
	seqreg(ret);
	return(ret);
    }

    public GLDrawList drawlist() {
	return(new GLDrawList(this));
    }

    public void reshape(Area wnd) {
	this.wnd = wnd;
    }

    public Area shape() {
	return(wnd);
    }

    private void checkqueries(GL2 gl) {
	for(Iterator<GLQuery> i = queries.iterator(); i.hasNext();) {
	    GLQuery query = i.next();
	    if(!query.check(gl))
		continue;
	    query.dispose();
	    i.remove();
	}
    }

    public void process(GL2 gl) {
	GLRender prep;
	Collection<GLRender> copy;
	synchronized(submitted) {
	    /* It is important to fetch the submitted renders before
	     * prep, so that additional once aren't submitted during
	     * processing that haven't been prepared. */
	    copy = new ArrayList<>(submitted);
	    submitted.clear();
	}
	synchronized(prepmon) {
	    prep = this.prep;
	    this.prep = null;
	}
	synchronized(drawmon) {
	    checkqueries(gl);
	    if((prep != null) && (prep.gl != null)) {
		BufferBGL xf = new BufferBGL(16);
		this.curstate.apply(xf, prep.init);
		xf.run(gl);
		prep.gl.run(gl);
		this.curstate = prep.state;
		try {
		    GLException.checkfor(gl);
		} catch(Exception exc) {
		    throw(new BGL.BGLException(prep.gl, null, exc));
		}
		sequnreg(prep);
	    }
	    for(GLRender cmd : copy) {
		BufferBGL xf = new BufferBGL(16);
		this.curstate.apply(xf, cmd.init);
		xf.run(gl);
		cmd.gl.run(gl);
		this.curstate = cmd.state;
		try {
		    GLException.checkfor(gl);
		} catch(Exception exc) {
		    throw(new BGL.BGLException(cmd.gl, null, exc));
		}
		sequnreg(cmd);
	    }
	    checkqueries(gl);
	    disposeall().run(gl);
	    clean();
	}
    }

    public void submit(Render cmd) {
	if(!(cmd instanceof GLRender))
	    throw(new IllegalArgumentException("environment mismatch"));
	GLRender gcmd = (GLRender)cmd;
	if(gcmd.env != this)
	    throw(new IllegalArgumentException("environment mismatch"));
	if(gcmd.gl != null) {
	    synchronized(submitted) {
		if(!invalid) {
		    submitted.add(gcmd);
		    submitted.notifyAll();
		} else {
		    gcmd.gl.abort();
		}
	    }
	}
    }

    public void submitwait() throws InterruptedException {
	synchronized(submitted) {
	    while(submitted.peek() == null)
		submitted.wait();
	}
    }

    private BufferBGL disposeall() {
	int tail;
	synchronized(seqmon) {
	    tail = seqtail;
	}
	BufferBGL buf = new BufferBGL();
	Collection<GLObject> copy;
	synchronized(disposed) {
	    if(disposed.isEmpty())
		return(buf);
	    copy = new ArrayList<>(disposed.size());
	    int lseq = 0;	// XXX: This assertion should be safe to remove once initially verified.
	    for(Iterator<GLObject> i = disposed.iterator(); i.hasNext();) {
		GLObject obj = i.next();
		if(obj.dispseq - lseq < 0)
		    throw(new AssertionError());
		if(obj.dispseq - tail > 0)
		    break;
		lseq = obj.dispseq;
		copy.add(obj);
		i.remove();
	    }
	}
	for(GLObject obj : copy)
	    buf.bglDelete(obj);
	buf.bglCheckErr();
	return(buf);
    }

    public FillBuffer fillbuf(DataBuffer tgt, int from, int to) {
	if((from == 0) && (to == tgt.size())) {
	    if((tgt instanceof VertexArray.Buffer) && (((VertexArray.Buffer)tgt).ro instanceof StreamBuffer))
		return(((StreamBuffer)(((VertexArray.Buffer)tgt).ro)).new Fill());
	    if((tgt instanceof Model.Indices) && (((Model.Indices)tgt).ro instanceof StreamBuffer))
		return(((StreamBuffer)(((Model.Indices)tgt).ro)).new Fill());
	}
	return(new FillBuffers.Array(to - from));
    }

    GLRender prepare() {
	if(prep == null) {
	    prep = new GLRender(this);
	    seqreg(prep);
	}
	return(prep);
    }
    void prepare(GLObject obj) {
	synchronized(prepmon) {
	    prepare().gl().bglCreate(obj);
	}
    }
    void prepare(BGL.Request req) {
	synchronized(prepmon) {
	    prepare().gl().bglSubmit(req);
	}
    }
    void prepare(Consumer<GLRender> func) {
	synchronized(prepmon) {
	    func.accept(prepare());
	}
    }

    Disposable prepare(Model.Indices buf) {
	synchronized(buf) {
	    switch(buf.usage) {
	    case EPHEMERAL: {
		if(!(buf.ro instanceof HeapBuffer)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new HeapBuffer(this, buf, buf.init);
		}
		return(buf.ro);
	    }
	    case STREAM: {
		StreamBuffer ret;
		if(!(buf.ro instanceof StreamBuffer) || ((ret = ((StreamBuffer)buf.ro)).rbuf.env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = ret = new StreamBuffer(this, buf.size());
		    if(buf.init != null) {
			StreamBuffer.Fill data = (StreamBuffer.Fill)buf.init.fill(buf, this);
			StreamBuffer jdret = ret;
			GLBuffer rbuf = ret.rbuf;
			prepare((GLRender g) -> {
				BGL gl = g.gl();
				Vao0State.apply(gl, g.state, rbuf);
				ByteBuffer xfbuf = data.get();
				gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, buf.size(), xfbuf, GL.GL_DYNAMIC_DRAW);
				jdret.put(gl, xfbuf);
				rbuf.setmem(MemStats.INDICES, buf.size());
			    });
		    }
		}
		return(ret);
	    }
	    case STATIC: {
		GLBuffer ret;
		if(!(buf.ro instanceof GLBuffer) || ((ret = ((GLBuffer)buf.ro)).env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = ret = new GLBuffer(this);
		    if(buf.init != null) {
			FillBuffers.Array data = (FillBuffers.Array)buf.init.fill(buf, this);
			GLBuffer jdret = ret;
			prepare((GLRender g) -> {
				BGL gl = g.gl();
				Vao0State.apply(gl, g.state, jdret);
				gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, buf.size(), ByteBuffer.wrap(data.data), GL.GL_STATIC_DRAW);
				jdret.setmem(MemStats.INDICES, buf.size());
			    });
		    }
		}
		return(ret);
	    }
	    default:
		throw(new Error());
	    }
	}
    }
    Disposable prepare(VertexArray.Buffer buf) {
	synchronized(buf) {
	    switch(buf.usage) {
	    case EPHEMERAL: {
		if(!(buf.ro instanceof HeapBuffer)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new HeapBuffer(this, buf, buf.init);
		}
		return(buf.ro);
	    }
	    case STREAM: {
		StreamBuffer ret;
		if(!(buf.ro instanceof StreamBuffer) || ((ret = ((StreamBuffer)buf.ro)).rbuf.env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = ret = new StreamBuffer(this, buf.size());
		    if(buf.init != null) {
			StreamBuffer.Fill data = (StreamBuffer.Fill)buf.init.fill(buf, this);
			StreamBuffer jdret = ret;
			GLBuffer rbuf = ret.rbuf;
			prepare((GLRender g) -> {
				BGL gl = g.gl();
				VboState.apply(gl, g.state, rbuf);
				ByteBuffer xfbuf = data.get();
				gl.glBufferData(GL.GL_ARRAY_BUFFER, buf.size(), xfbuf, GL.GL_DYNAMIC_DRAW);
				jdret.put(gl, xfbuf);
				rbuf.setmem(MemStats.VERTICES, buf.size());
			    });
		    }
		}
		return(ret);
	    }
	    case STATIC: {
		GLBuffer ret;
		if(!(buf.ro instanceof GLBuffer) || ((ret = ((GLBuffer)buf.ro)).env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = ret = new GLBuffer(this);
		    if(buf.init != null) {
			FillBuffers.Array data = (FillBuffers.Array)buf.init.fill(buf, this);
			GLBuffer jdret = ret;
			prepare((GLRender g) -> {
				BGL gl = g.gl();
				VboState.apply(gl, g.state, jdret);
				gl.glBufferData(GL.GL_ARRAY_BUFFER, buf.size(), ByteBuffer.wrap(data.data), GL.GL_STATIC_DRAW);
				jdret.setmem(MemStats.VERTICES, buf.size());
			    });
		    }
		}
		return(ret);
	    }
	    default:
		throw(new Error());
	    }
	}
    }
    GLVertexArray prepare(Model mod, GLProgram prog) {
	synchronized(mod) {
	    GLVertexArray.ProgIndex idx;
	    if(!(mod.ro instanceof GLVertexArray.ProgIndex) || ((idx = ((GLVertexArray.ProgIndex)mod.ro)).env != this)) {
		if(mod.ro != null)
		    mod.ro.dispose();
		mod.ro = idx = new GLVertexArray.ProgIndex(mod, this);
	    }
	    return(idx.get(prog));
	}
    }
    GLTexture.Tex2D prepare(Texture2D tex) {
	synchronized(tex) {
	    GLTexture.Tex2D ret;
	    if(!(tex.ro instanceof GLTexture.Tex2D) || ((ret = (GLTexture.Tex2D)tex.ro).env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = ret = GLTexture.Tex2D.create(this, tex);
	    }
	    return(ret);
	}
    }
    GLTexture.Tex2D prepare(Texture2D.Sampler2D smp) {
	Texture2D tex = smp.tex;
	synchronized(tex) {
	    GLTexture.Tex2D ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }
    GLTexture.TexCube prepare(TextureCube tex) {
	synchronized(tex) {
	    GLTexture.TexCube ret;
	    if(!(tex.ro instanceof GLTexture.TexCube) || ((ret = (GLTexture.TexCube)tex.ro).env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = ret = GLTexture.TexCube.create(this, tex);
	    }
	    return(ret);
	}
    }
    GLTexture.TexCube prepare(TextureCube.SamplerCube smp) {
	TextureCube tex = smp.tex;
	synchronized(tex) {
	    GLTexture.TexCube ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }

    Object prepuval(Object val) {
	if(val instanceof Texture.Sampler) {
	    if(val instanceof Texture2D.Sampler2D)
		return(prepare((Texture2D.Sampler2D)val));
	    else if(val instanceof TextureCube.SamplerCube)
		return(prepare((TextureCube.SamplerCube)val));
	}
	return(val);
    }

    Object prepfval(Object val) {
	if(val instanceof Texture.Image)
	    return(GLFrameBuffer.prepimg(this, (Texture.Image)val));
	return(val);
    }

    public class TempData<T> implements Supplier<T> {
	private final Supplier<T> bk;
	private T d = null;

	public TempData(Supplier<T> bk) {this.bk = bk;}

	public T get() {
	    if(d == null) {
		synchronized(this) {
		    if(d == null)
			d = bk.get();
		}
	    }
	    return(d);
	}
    }

    public final Supplier<GLBuffer> tempvertex = new TempData<>(() -> new GLBuffer(this));
    public final Supplier<GLBuffer> tempindex = new TempData<>(() -> new GLBuffer(this));

    static class SavedProg {
	final int hash;
	final ShaderMacro[] shaders;
	final GLProgram prog;
	SavedProg next;
	boolean used = true;

	SavedProg(int hash, ShaderMacro[] shaders, GLProgram prog) {
	    this.hash = hash;
	    this.shaders = Arrays.copyOf(shaders, shaders.length);
	    this.prog = prog;
	}
    }

    private final Object pmon = new Object();
    private SavedProg[] ptab = new SavedProg[32];
    private int nprog = 0;
    private SavedProg findprog(int hash, ShaderMacro[] shaders) {
	int idx = hash & (ptab.length - 1);
	outer: for(SavedProg s = ptab[idx]; s != null; s = s.next) {
	    if(s.hash != hash)
		continue;
	    ShaderMacro[] a, b;
	    if(shaders.length < s.shaders.length) {
		a = shaders; b = s.shaders;
	    } else {
		a = s.shaders; b = shaders;
	    }
	    int i = 0;
	    for(; i < a.length; i++) {
		if(a[i] != b[i])
		    continue outer;
	    }
	    for(; i < b.length; i++) {
		if(b[i] != null)
		    continue outer;
	    }
	    return(s);
	}
	return(null);
    }

    private void rehash(int nlen) {
	SavedProg[] ntab = new SavedProg[nlen];
	for(int i = 0; i < ptab.length; i++) {
	    while(ptab[i] != null) {
		SavedProg s = ptab[i];
		ptab[i] = s.next;
		int ni = s.hash & (nlen - 1);
		s.next = ntab[ni];
		ntab[ni] = s;
	    }
	}
	ptab = ntab;
    }

    private void putprog(int hash, ShaderMacro[] shaders, GLProgram prog) {
	int idx = hash & (ptab.length - 1);
	SavedProg save = new SavedProg(hash, shaders, prog);
	save.next = ptab[idx];
	ptab[idx] = save;
	nprog++;
	if(nprog > ptab.length)
	    rehash(ptab.length * 2);
    }

    public GLProgram getprog(int hash, ShaderMacro[] shaders) {
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		s.used = true;
		return(s.prog);
	    }
	}
	Collection<ShaderMacro> mods = new LinkedList<>();
	for(int i = 0; i < shaders.length; i++) {
	    if(shaders[i] != null)
		mods.add(shaders[i]);
	}
	GLProgram prog = GLProgram.build(this, mods);
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		prog.dispose();
		s.used = true;
		return(s.prog);
	    }
	    putprog(hash, shaders, prog);
	    return(prog);
	}
    }

    private void cleanprogs() {
	synchronized(pmon) {
	    for(int i = 0; i < ptab.length; i++) {
		SavedProg c, p;
		for(c = ptab[i], p = null; c != null; c = c.next) {
		    int rc = c.prog.locked.get();
		    if(c.used || (rc > 0)) {
			if(rc < 1)
			    c.used = false;
			p = c;
		    } else {
			if(p == null)
			    ptab[i] = c.next;
			else
			    p.next = c.next;
			c.prog.dispose();
			nprog--;
		    }
		}
	    }
	    /* XXX: Rehash into smaller table? It's probably not a
	     * problem, but it might be nice just for
	     * completeness. */
	}
    }

    private double lastpclean = Utils.rtime();
    public void clean() {
	double now = Utils.rtime();
	if(now - lastpclean > 60) {
	    cleanprogs();
	    lastpclean = now;
	}
    }

    private final Object seqmon = new Object();
    private boolean[] sequse = new boolean[16];
    private int seqhead = 1, seqtail = 1;

    private void seqresize(int nsz) {
	boolean[] cseq = sequse, nseq = new boolean[nsz];
	int csz = cseq.length;
	for(int i = 0; i < csz; i++)
	    nseq[(seqtail + i) & (nsz - 1)] = cseq[(seqtail + i) & (csz - 1)];
	sequse = nseq;
	if(nsz >= 0x4000)
	    System.err.println("warning: dispose queue size increased to " + nsz);
    }

    void seqreg(GLRender r) {
	synchronized(seqmon) {
	    if(r.dispseq != 0)
		throw(new IllegalStateException());
	    int seq = r.dispseq = seqhead;
	    if(++seqhead == 0)
		seqhead = 1;
	    if(seqhead - seqtail == sequse.length - 1)
		seqresize(sequse.length << 1);
	    sequse[seq & (sequse.length - 1)] = true;
	}
    }

    void sequnreg(GLRender r) {
	synchronized(seqmon) {
	    if(r.dispseq == 0)
		return;
	    int seq = r.dispseq, m = sequse.length - 1;
	    int si = seq & m;
	    if(!sequse[si])
		throw(new AssertionError());
	    sequse[si] = false;
	    if(seq == seqtail) {
		while((seqtail < seqhead) && !sequse[seqtail & m])
		    seqtail++;
	    }
	    r.dispseq = 0;
	}
    }

    int dispseq() {
	synchronized(seqmon) {
	    return(seqhead);
	}
    }

    public int numprogs() {return(nprog);}

    public String memstats() {
	StringBuilder buf = new StringBuilder();
	MemStats[] sta = MemStats.values();
	for(int i = 0; i < sta.length; i++) {
	    if(i > 0)
		buf.append(" / ");
	    buf.append(String.format("%c %,d (%,d)", sta[i].name().charAt(0), stats_mem[i], stats_obj[i]));
	}
	return(buf.toString());
    }

    public void dispose() {
	Collection<GLRender> copy;
	synchronized(submitted) {
	    copy = new ArrayList<>(submitted);
	    submitted.clear();
	    invalid = true;
	}
	for(GLRender cmd : copy) {
	    cmd.gl.abort();
	    sequnreg(cmd);
	}
	/* XXX: Provide a way to abort pending queries? */
    }
}
