import {Input,BlobSource,ALL_FORMATS,AudioSampleSink} from 'mediabunny';
export async function extract(file,start,end){
 if(end<=start||end-start>30.1)throw Error('Choose a section of at most 30 seconds.');
 let input;
 try{
  input=new Input({source:new BlobSource(file),formats:ALL_FORMATS});
  const track=await input.getPrimaryAudioTrack();
  if(!track||!await track.canDecode())throw Error('This browser cannot decode the audio format. Try current Chrome or Edge, or an MP3/WAV chapter.');
  const sink=new AudioSampleSink(track);let raw,sampleRate=0;
  for await(const sample of sink.samples(start,end)){
   try{
    const buffer=sample.toAudioBuffer();
    if(!sampleRate){sampleRate=buffer.sampleRate;raw=new Float32Array(Math.ceil((end-start)*sampleRate));}
    if(buffer.sampleRate!==sampleRate)throw Error('Audio sample rate changed inside this chapter.');
    const offset=Math.round((sample.timestamp-start)*sampleRate);
    const channels=Array.from({length:buffer.numberOfChannels},(_,i)=>buffer.getChannelData(i));
    for(let n=Math.max(0,-offset);n<buffer.length&&n+offset<raw.length;n++){
      let value=0;for(const channel of channels)value+=channel[n];raw[n+offset]=value/channels.length;
    }
   }finally{sample.close();}
  }
  if(!raw)throw Error('No audio could be decoded in this section.');
  const renderer=new OfflineAudioContext(1,Math.ceil((end-start)*16000),16000);
  const source=renderer.createBufferSource();source.buffer=renderer.createBuffer(1,raw.length,sampleRate);source.buffer.copyToChannel(raw,0);source.connect(renderer.destination);source.start();
  return (await renderer.startRendering()).getChannelData(0);
 }finally{input?.dispose();}
}
