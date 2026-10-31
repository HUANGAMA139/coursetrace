import { sha256 } from "@noble/hashes/sha2.js";
self.onmessage = async ({data: file}) => {
  try {
    const hash=sha256.create(),step=8*1024*1024;
    for(let offset=0;offset<file.size;offset+=step){
      hash.update(new Uint8Array(await file.slice(offset,offset+step).arrayBuffer()));
      self.postMessage({progress:Math.min(100,Math.round((offset+step)/file.size*100))});
    }
    self.postMessage({sha256:Array.from(hash.digest(),b=>b.toString(16).padStart(2,"0")).join("")});
  }catch(error){self.postMessage({error:error.message});}
};
