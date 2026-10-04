package com.filemanager;

import java.io.*;
import java.util.*;
import java.util.zip.*;

class DexItem extends ZipItem {
	final String dexEntry;
	final String virtualPath;
	final boolean virtualDir;
	final int kind; // 0 root/package, 1 class, 2 Fields, 3 Methods, 4 member
	final String classDescriptor;

	DexItem(File zip, String dexEntry, String virtualPath, boolean dir, long size, long time) {
		this(zip, dexEntry, virtualPath, dir, size, time, 0, null);
	}

	DexItem(File zip, String dexEntry, String virtualPath, boolean dir, long size, long time,
			int kind, String descriptor) {
		super(zip, dexEntry, true, size, time);
		this.dexEntry = dexEntry;
		this.virtualPath = virtualPath == null ? "" : virtualPath;
		this.virtualDir = dir;
		this.kind = kind;
		this.classDescriptor = descriptor;
	}

	boolean isDexRoot() { return virtualPath.length() == 0 && kind == 0; }

	public String getName() {
		if (isDexRoot()) {
			int i=dexEntry.lastIndexOf('/');
			return i<0?dexEntry:dexEntry.substring(i+1);
		}
		int i=virtualPath.lastIndexOf('/');
		return i<0?virtualPath:virtualPath.substring(i+1);
	}
	public boolean isDirectory(){ return virtualDir && zip.exists(); }
	public boolean isFile(){ return !virtualDir; }
	public long length(){ return isDexRoot()?size:0; }
	public String getAbsolutePath(){ return zip.getAbsolutePath()+"!/"+dexEntry+"!dex/"+virtualPath; }
	public String getPath(){ return getAbsolutePath(); }

	public File getParentFile() {
		if(isDexRoot()) return new ZipItem(zip,parentEntry(dexEntry),true,0,zip.lastModified());
		int i=virtualPath.lastIndexOf('/');
		if(i<0) return new DexItem(zip,dexEntry,"",true,size,time);
		return new DexItem(zip,dexEntry,virtualPath.substring(0,i),true,0,time);
	}
	static String parentEntry(String e){ int i=e.lastIndexOf('/'); return i<0?"":e.substring(0,i); }

	public File[] listFiles() {
		if(!virtualDir) return null;
		try {
			DexData data=readDex(zip,dexEntry);
			if(kind==1) {
				ArrayList<File> r=new ArrayList<File>();
				ClassInfo ci=data.findClass(classDescriptor);
				if(ci!=null && ci.fields.size()>0)
					r.add(new DexItem(zip,dexEntry,virtualPath+"/Fields",true,0,time,2,classDescriptor));
				if(ci!=null && ci.methods.size()>0)
					r.add(new DexItem(zip,dexEntry,virtualPath+"/Methods",true,0,time,3,classDescriptor));
				return r.toArray(new File[0]);
			}
			if(kind==2 || kind==3) {
				ClassInfo ci=data.findClass(classDescriptor);
				if(ci==null) return new File[0];
				ArrayList<String> names=kind==2?ci.fields:ci.methods;
				File[] r=new File[names.size()];
				for(int i=0;i<names.size();i++)
					r[i]=new DexItem(zip,dexEntry,virtualPath+"/"+names.get(i),false,0,time,4,classDescriptor);
				return r;
			}

			LinkedHashMap<String,File> out=new LinkedHashMap<String,File>();
			String prefix=virtualPath.length()==0?"":virtualPath+"/";
			for(int i=0;i<data.classes.size();i++) {
				ClassInfo ci=data.classes.get(i);
				String path=ci.path;
				if(!path.startsWith(prefix)||path.length()<=prefix.length()) continue;
				String rest=path.substring(prefix.length());
				int slash=rest.indexOf('/');
				String child=slash<0?rest:rest.substring(0,slash);
				if(child.length()==0||out.containsKey(child)) continue;
				if(slash>=0)
					out.put(child,new DexItem(zip,dexEntry,prefix+child,true,0,time,0,null));
				else
					out.put(child,new DexItem(zip,dexEntry,prefix+child,true,0,time,1,ci.descriptor));
			}
			return out.values().toArray(new File[0]);
		} catch(Exception e) { return new File[0]; }
	}

	static class ClassInfo {
		String descriptor,path;
		ArrayList<String> fields=new ArrayList<String>();
		ArrayList<String> methods=new ArrayList<String>();
	}
	static class DexData {
		ArrayList<ClassInfo> classes=new ArrayList<ClassInfo>();
		ClassInfo findClass(String d){
			for(int i=0;i<classes.size();i++) if(classes.get(i).descriptor.equals(d)) return classes.get(i);
			return null;
		}
	}

	static final java.util.concurrent.ConcurrentHashMap<String, DexData> dexCache = new java.util.concurrent.ConcurrentHashMap<String, DexData>();

	static void clearCache() {
		dexCache.clear();
	}

	static DexData readDex(File apk,String entry)throws IOException {
		String key = apk.getAbsolutePath() + "!" + entry;
		DexData cachedHit = dexCache.get(key);
		if (cachedHit != null) return cachedHit;
		byte[] d=readEntry(apk,entry);
		DexData out=new DexData();
		if(d.length<112||d[0]!='d'||d[1]!='e'||d[2]!='x') { dexCache.put(key, out); return out; }

		int stringSize=le32(d,0x38), stringOff=le32(d,0x3c);
		int typeSize=le32(d,0x40), typeOff=le32(d,0x44);
		int protoSize=le32(d,0x48), protoOff=le32(d,0x4c);
		int fieldSize=le32(d,0x50), fieldOff=le32(d,0x54);
		int methodSize=le32(d,0x58), methodOff=le32(d,0x5c);
		int classSize=le32(d,0x60), classOff=le32(d,0x64);
		if(!range(d,stringOff,stringSize,4)||!range(d,typeOff,typeSize,4)||!range(d,classOff,classSize,32)) { dexCache.put(key, out); return out; }

		String[] strings=new String[stringSize];
		for(int i=0;i<stringSize;i++) strings[i]=dexString(d,le32(d,stringOff+i*4));
		String[] types=new String[typeSize];
		for(int i=0;i<typeSize;i++){ int si=le32(d,typeOff+i*4); types[i]=(si>=0&&si<strings.length)?strings[si]:"?"; }

		String[] fieldNames=new String[Math.max(0,fieldSize)];
		String[] fieldOwners=new String[Math.max(0,fieldSize)];
		if(range(d,fieldOff,fieldSize,8)) for(int i=0;i<fieldSize;i++){
			int q=fieldOff+i*8, owner=u16(d,q), type=u16(d,q+2), ni=le32(d,q+4);
			fieldOwners[i]=owner>=0&&owner<types.length?types[owner]:"?";
			String n=ni>=0&&ni<strings.length?strings[ni]:"?";
			String t=type>=0&&type<types.length?prettyType(types[type]):"?";
			fieldNames[i]=n+" : "+t;
		}

		String[] methodNames=new String[Math.max(0,methodSize)];
		String[] methodOwners=new String[Math.max(0,methodSize)];
		if(range(d,methodOff,methodSize,8)) for(int i=0;i<methodSize;i++){
			int q=methodOff+i*8, owner=u16(d,q), proto=u16(d,q+2), ni=le32(d,q+4);
			methodOwners[i]=owner>=0&&owner<types.length?types[owner]:"?";
			String n=ni>=0&&ni<strings.length?strings[ni]:"?";
			methodNames[i]=n+protoSignature(d,proto,protoSize,protoOff,types);
		}

		for(int i=0;i<classSize;i++){
			int q=classOff+i*32, classIdx=le32(d,q), dataOff=le32(d,q+24);
			if(classIdx<0||classIdx>=types.length) continue;
			String desc=types[classIdx];
			if(desc==null||desc.length()<3||desc.charAt(0)!='L'||desc.charAt(desc.length()-1)!=';') continue;
			ClassInfo ci=new ClassInfo(); ci.descriptor=desc;
		
			String name=desc.substring(1,desc.length()-1);
			ci.path=name;

			if(dataOff>0&&dataOff<d.length){
				int[] pos=new int[]{dataOff};
				int sf=uleb(d,pos), inf=uleb(d,pos), dm=uleb(d,pos), vm=uleb(d,pos);
				int idx=0;
				for(int k=0;k<sf+inf;k++){
					idx+=uleb(d,pos); uleb(d,pos);
					if(idx>=0&&idx<fieldNames.length&&desc.equals(fieldOwners[idx])&&!ci.fields.contains(fieldNames[idx])) ci.fields.add(fieldNames[idx]);
				}
				idx=0;
				for(int k=0;k<dm+vm;k++){
					idx+=uleb(d,pos); uleb(d,pos); uleb(d,pos);
					if(idx>=0&&idx<methodNames.length&&desc.equals(methodOwners[idx])&&!ci.methods.contains(methodNames[idx])) ci.methods.add(methodNames[idx]);
				}
			}
			Collections.sort(ci.fields,String.CASE_INSENSITIVE_ORDER);
			Collections.sort(ci.methods,String.CASE_INSENSITIVE_ORDER);
			out.classes.add(ci);
		}
		Collections.sort(out.classes,new Comparator<ClassInfo>(){
			public int compare(ClassInfo a,ClassInfo b){ return a.path.compareToIgnoreCase(b.path); }
		});
		dexCache.put(key, out);
		return out;
	}

	static byte[] readEntry(File apk,String entry)throws IOException{
		PZip zf=null; InputStream in=null;
		try{
			zf=new PZip(apk); PEntry ze=zf.getEntry(entry); if(ze==null)return new byte[0];
			in=zf.getInputStream(ze); ByteArrayOutputStream b=new ByteArrayOutputStream();
			byte[] x=new byte[65536]; int n; while((n=in.read(x))>0)b.write(x,0,n); return b.toByteArray();
		}finally{
			if(in!=null)try{in.close();}catch(Exception e){}
			if(zf!=null)try{zf.close();}catch(Exception e){}
		}
	}
	static String protoSignature(byte[] d,int pi,int ps,int po,String[] types){
		if(pi<0||pi>=ps||!range(d,po+pi*12,1,12)) return "()";
		int q=po+pi*12, ret=le32(d,q+4), params=le32(d,q+8);
		StringBuilder b=new StringBuilder("(");
		if(params>0&&params+4<=d.length){
			int count=le32(d,params);
			for(int i=0;i<count&&params+4+i*2+1<d.length;i++){
				if(i>0)b.append(", ");
				int ti=u16(d,params+4+i*2);
				b.append(ti>=0&&ti<types.length?prettyType(types[ti]):"?");
			}
		}
		b.append(") : ");
		b.append(ret>=0&&ret<types.length?prettyType(types[ret]):"?");
		return b.toString();
	}
	static String prettyType(String x){
		if(x==null)return "?";
		int arr=0; while(x.startsWith("[")){arr++;x=x.substring(1);}
		String r;
		if("V".equals(x))r="void"; else if("Z".equals(x))r="boolean"; else if("B".equals(x))r="byte";
		else if("S".equals(x))r="short"; else if("C".equals(x))r="char"; else if("I".equals(x))r="int";
		else if("J".equals(x))r="long"; else if("F".equals(x))r="float"; else if("D".equals(x))r="double";
		else if(x.startsWith("L")&&x.endsWith(";"))r=x.substring(1,x.length()-1).replace('/','.');
		else r=x;
		for(int i=0;i<arr;i++)r+="[]"; return r;
	}
	static int u16(byte[] d,int p){ if(p<0||p+1>=d.length)return -1; return (d[p]&255)|((d[p+1]&255)<<8); }
	static int uleb(byte[] d,int[] pos){
		int r=0,shift=0,count=0;
		while(pos[0]<d.length&&count<5){int b=d[pos[0]++]&255;r|=(b&127)<<shift;count++;if((b&128)==0)break;shift+=7;}
		return r;
	}
	static boolean range(byte[] d,int off,int count,int width){
		if(off<0||count<0||width<=0)return false;
		return (long)off+(long)count*width<=d.length;
	}
	static int le32(byte[] d,int p){
		if(p<0||p+3>=d.length)return -1;
		return(d[p]&255)|((d[p+1]&255)<<8)|((d[p+2]&255)<<16)|((d[p+3]&255)<<24);
	}
	static String dexString(byte[] d,int p){
		if(p<0||p>=d.length)return null;
		int n=0; while(p<d.length&&n<5){int b=d[p++]&255;n++;if((b&128)==0)break;}
		ByteArrayOutputStream b=new ByteArrayOutputStream();
		while(p<d.length&&d[p]!=0)b.write(d[p++]);
		try{return new String(b.toByteArray(),"UTF-8");}catch(Exception e){return new String(b.toByteArray());}
	}
}
