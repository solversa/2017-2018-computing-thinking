package info.smartkit.godpaper.go.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import info.smartkit.godpaper.go.dto.PlayMessage;
import info.smartkit.godpaper.go.dto.SgfDto;
import info.smartkit.godpaper.go.dto.SgfObj;
import info.smartkit.godpaper.go.pojo.Gamer;
import info.smartkit.godpaper.go.pojo.User;
import info.smartkit.godpaper.go.repository.AierRepository;
import info.smartkit.godpaper.go.repository.GamerRepository;
import info.smartkit.godpaper.go.repository.UserRepository;
import info.smartkit.godpaper.go.service.*;
import info.smartkit.godpaper.go.service.sse.ScheduledService;
import info.smartkit.godpaper.go.settings.*;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.projectodd.stilts.stomp.StompException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.jms.JMSException;
import javax.net.ssl.SSLException;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Created by smartkit on 22/06/2017.
 */
@RestController
@RequestMapping("/game")
public class GameController {
        //
        private static Logger LOG = LogManager.getLogger(GameController.class);

        @Autowired GamerRepository repository;
        @Autowired UserRepository userRepository;
        @Autowired GamerService service;
        @Autowired MqttService mqttService;
        @Autowired DockerService dockerService;
        @Autowired AierRepository aierRepository;
        @Autowired GamerRepository gamerRepository;
        @Autowired StompService stompService;
        @Autowired
        MqttProperties mqttProperties;
        @Autowired ChainCodeProperties chainCodeProperties;
        @Autowired ApiProperties apiProperties;

        @Autowired SocketIoService socketIoService;
        @Autowired AiProperties aiProperties;

        @RequestMapping(method = RequestMethod.POST)
        public Gamer createOne(@RequestBody Gamer gamer) throws IOException, InterruptedException, URISyntaxException, TimeoutException, StompException, JMSException {
                Gamer result = repository.save(gamer);
                //gamer folder creating.
                service.createFolder(result.getId());
                return result;
        }
        @RequestMapping(method = RequestMethod.GET,value="/pair")
        public List<Gamer> pairAll() throws MqttException {
                List<User> tenantedUsers = userRepository.findByStatus(UserStatus.TENANTED.getIndex());
                LOG.info("tenantedUsers("+tenantedUsers.size()+"):"+tenantedUsers.toString());
                //
                List<Gamer> pairedGames = service.pairAll(tenantedUsers);
                LOG.info("pairedGames("+pairedGames.size()+"):"+pairedGames.toString());
                return pairedGames;
        }

        @RequestMapping(method = RequestMethod.GET,value="/play")
        public List<Gamer> playAll() throws MqttException, DockerException, InterruptedException, IOException {
                return service.playAll();
        }

        @RequestMapping(method = RequestMethod.GET,value="/play/{gamerId}")
        public Gamer playOne(@PathVariable String gamerId) throws Exception {
                //
                return service.playOne(gamerId);
        }

        @RequestMapping(method = RequestMethod.GET, value="/{gamerId}")
        public Gamer get(@PathVariable String gamerId){
                return repository.findOne(gamerId);
        }

        @RequestMapping(method = RequestMethod.GET)
        public List<Gamer> listAll() throws InterruptedException, SSLException, URISyntaxException, TimeoutException, JMSException, StompException {
                List<Gamer> all = repository.findAllByOrderByCreatedDesc();
//                for(Gamer gamer:all) {
//                        //stomp get ready if has any human player.
//                        if (hasHumanPlayer(gamer)) {
//                                service.connectHumanPlayer(gamer);
//                        }
//                }
                return all;
        }

        @RequestMapping(method = RequestMethod.GET, value="/saved")
        public List<Gamer> listSaved(){
                return repository.findByStatus(GameStatus.SAVED.getIndex());
        }


        @RequestMapping(method = RequestMethod.DELETE, value="/{gamerId}")
        public void deleteOne(@PathVariable String gamerId) throws MqttException, IOException, StompException {
                //Dismiss gamer's user
                Gamer gamer = repository.findOne(gamerId);
                //mqtt unsubscribe
                mqttService.unsubscribe(gamer.getTopic());
                User player1 = gamer.getPlayer1();
                player1.setStatus(UserStatus.unTENANTED.getIndex());
                userRepository.save(player1);
                User player2 = gamer.getPlayer2();
                player2.setStatus(UserStatus.unTENANTED.getIndex());
                userRepository.save(player2);
                //
                repository.delete(gamerId);
                //gamer folder deleting.
                service.deleteFolder(gamerId);
                //stomp unsubscribe
//                if(hasHumanPlayer(gamer)){
//                        if(stompService.isConnected()) {
//                                stompService.unsubscribe();
//                        }
//                }
                stopSseEmitter(gamerId);
        }
        @RequestMapping(method = RequestMethod.DELETE, value="/")
        public void deleteAll() throws MqttException, StompException {
                List<Gamer> allGamers = repository.findAll();
                for(Gamer gamer : allGamers){
                        //unsubscribe
                        mqttService.unsubscribe(gamer.getTopic());
                        //stomp unsubscribe
                        if(hasHumanPlayer(gamer)){
                                stompService.unsubscribe();
                        }
                        //
                        User player1 = gamer.getPlayer1();
                        player1.setStatus(UserStatus.unTENANTED.getIndex());
                        userRepository.save(player1);
                        User player2 = gamer.getPlayer2();
                        player2.setStatus(UserStatus.unTENANTED.getIndex());
                        userRepository.save(player2);
                }
                //
                repository.deleteAll();
        }

        @RequestMapping(method = RequestMethod.GET, value="/sgf/{gamerId}")
        public SgfDto saveSgfById(@PathVariable String gamerId) throws IOException, DockerException, InterruptedException {
                Gamer gamer = repository.findOne(gamerId);
                return service.saveSgf(gamer,true);
        }

        @RequestMapping(method = RequestMethod.GET, value="/result/{gamerId}")
        public String getResultById(@PathVariable String gamerId) throws Exception {
                //
                Gamer gamer = repository.findOne(gamerId);
//                if(gamer.getStatus()!=GameStatus.SAVED.getIndex()){
                if(gamer.getResult()==null){
                        return service.getSgfResult(gamer);
                }
                return gamer.getResult().trim();
        }

        @RequestMapping(method = RequestMethod.GET,value="/play/r/{gamerNum}")
        public void playOne(@PathVariable int gamerNum) throws MqttException, DockerException, InterruptedException, IOException {
                service.randomPlaySome(gamerNum);
        }

//        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        @Autowired
        private ScheduledService scheduledService;
        @RequestMapping(method = RequestMethod.GET, value="/sse/sgf/{gamerId}")
        public ResponseBodyEmitter getSseSgfById(@PathVariable String gamerId,HttpSession session) {
//                SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
//                //
//                Thread t1 = new Thread(() ->{
//                        try {
//                                int i = 0;
//                                int max =10;
////                                int max = apiProperties.getSse()*361;
//                                while(++i<=max){
//                                        Thread.sleep(apiProperties.getSse());
//                                        System.out.println(gamerId+",SSE sgf Sending...."+i);
//                                        try{
//                                                Gamer gamer = gamerRepository.findOne(gamerId);
//                                                if(gamer!=null) {
//                                                        emitter.send(gamer.getSgf(),MediaType.TEXT_PLAIN);
//                                                }else{
//                                                        LOG.warn("Not found with this gamerId:"+gamerId);
//                                                }
//                                        }catch(ClientAbortException cae){
//                                                cae.printStackTrace();
//                                                i = max;
//                                        }
//                                }
//                                emitter.complete();
//                        } catch (IOException | InterruptedException e) {
//                                e.printStackTrace();
//                        }
//                });
//                t1.start();

                final SseEmitter emitter = new SseEmitter();
                ExecutorService service = Executors.newSingleThreadExecutor();
                service.execute(() -> {
                        int max = apiProperties.getSse()*361;
                        for (int i = 0; i < max; i++) {
                                try {
                                        Gamer gamer = gamerRepository.findOne(gamerId);
                                        emitter.send(gamer.getSgf(),MediaType.TEXT_PLAIN);
                                        System.out.println(gamerId+",SSE sgf Sending...."+i);
                                        Thread.sleep(500);
                                } catch (Exception e) {
                                        e.printStackTrace();
                                        emitter.completeWithError(e);
                                        return;
                                }
                        }
                        emitter.complete();
                });
                //
                if(GameVariables.sseEmitters.get(gamerId)==null) {
//                        SseEmitter sseEmitter = scheduledService.getInfiniteMessages(gamerId);
                        GameVariables.sseEmitters.put(gamerId, emitter);
                }
                return emitter;
        }


        @RequestMapping(method = RequestMethod.GET, value="/sio/sgf/{gamerId}/{playerId}")
        public void joinSioSgfById(@PathVariable String gamerId,@PathVariable String playerId) {
                socketIoService.join(UUID.fromString(playerId),gamerId);
        }

        private void stopSseEmitter(@PathVariable String gamerId) {
                SseEmitter sseEmitter = GameVariables.sseEmitters.get(gamerId);
                if(sseEmitter!=null) {
                        sseEmitter.complete();
                }
        }

        @RequestMapping(method = RequestMethod.DELETE, value="/sio/sgf/{gamerId}/{playerId}")
        public void leaveSioSgfById(@PathVariable String gamerId,@PathVariable String playerId) {
                socketIoService.leave(UUID.fromString(playerId),gamerId);
        }

        @RequestMapping(method = RequestMethod.PUT, value="/sgf/{gamerId}/{playerId}")
        public SgfObj updateSgfById(@RequestBody SgfObj sgfObj, @PathVariable String gamerId, @PathVariable String playerId) {
                SgfObj sgfObjUpdate = updateSgfObj(sgfObj.getBody(), gamerId);
                //SocketIO broadcasting
                PlayMessage playMessage = new PlayMessage();
                playMessage.setGame_id(gamerId);
                playMessage.setUser_id(playerId);
                playMessage.setMethod("play");
                playMessage.setMsg(sgfObj.getBody());
//                socketIoService.emit(playMessage.getMethod(),playMessage);
                SocketIoVariables.server.getBroadcastOperations().sendEvent("playEvent", playMessage);
                return sgfObjUpdate;
        }

        @RequestMapping(method = RequestMethod.PUT, value="/{gamerId}/{status}")
        public Gamer updateStatusById(@PathVariable String gamerId, @PathVariable int status) throws InterruptedException, DockerException, IOException {
                Gamer updater = repository.findOne(gamerId);
                //save sgf file.
                //merge sgf string.
                String sgfHeader  = service.getSgfHeader(chainCodeProperties.getChainName(),"0.0.1",updater,"B?R");
                String sgfBody = updater.getSgf();
                updater.setSgf(sgfHeader.concat(sgfBody)+")");
                service.saveSgf(updater,false);
                updater.setStatus(status);
               return repository.save(updater);
        }

        @RequestMapping(method = RequestMethod.PUT, value="/{gamerId}/{userId}/{status}")
        public Gamer updateStatusByUserId(@PathVariable String gamerId, @PathVariable int status,@PathVariable String userId) throws InterruptedException, DockerException, IOException {
                Gamer updater = repository.findOne(gamerId);
//                updater.setStatus(GameStatus.PLAYING.getIndex());
                updater.getPlayer(userId).setStatus(status);
                return repository.save(updater);
        }

        private SgfObj updateSgfObj(String sgfBody, @PathVariable String gamerId) {
                Gamer gamer  = repository.findOne(gamerId);
                String sgfHeader  = service.getSgfHeader(chainCodeProperties.getChainName(),"0.0.1",gamer,"B?R");
                String oriSgf = gamer.getSgf();
                //ignore duplicated
                String newSgf = oriSgf;
                if(!oriSgf.contains(sgfBody)){
                        newSgf = oriSgf.concat(sgfBody);
                }
                //
                gamer.setSgf(newSgf);
                //status update
                gamer.setStatus(GameStatus.PLAYING.getIndex());
                repository.save(gamer);
                //
                return new SgfObj(sgfHeader,newSgf);
        }

        @RequestMapping(method = RequestMethod.POST,value="/ai/simple/{gamerId}")
        public PlayMessage vsSimpleAI(@RequestBody PlayMessage playMessage, @PathVariable String gamerId) throws IOException {
                //post to simple AI server.
                ClientHttpRequestFactory requestFactory = new
                        HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());
//                RestTemplate restTemplate = new RestTemplate((ClientHttpRequestFactory) new SkGpGsonHttpMessageConverter());
                RestTemplate restTemplate = new RestTemplate(requestFactory);
//                ArrayList varList = new ArrayList();
                //
                Gamer gamer  = repository.findOne(gamerId);
                String sgfHeader  = service.getSgfHeader(chainCodeProperties.getChainName(),"0.0.1",gamer,"B?R");
                String sgfBody = gamer.getSgf().replace(sgfHeader,"");

                Map<String, String> vars = new HashMap<String, String>();
                vars.put("game_id", playMessage.getGame_id());
                vars.put("user_id", playMessage.getUser_id());
                vars.put("msg", sgfBody);
//
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
//                headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
                HttpEntity<Object> request = new HttpEntity<>(vars, headers);
//
                ResponseEntity<String> response = restTemplate
                        .exchange("http://" + mqttProperties.getIp() + ":"+aiProperties.getPort(), HttpMethod.POST, request, String.class);
                LOG.debug("response:" + response.toString());
                //
                ObjectMapper objectMapper = new ObjectMapper();
                PlayMessage rPlayMessage = objectMapper.readValue(response.getBody(), PlayMessage.class);
                LOG.info("SimpleAI response.playMessage:" + rPlayMessage.toString());
        //update gamer
                updateSgfObj(";"+rPlayMessage.getMsg(), playMessage.getGame_id());
                //
                return rPlayMessage;
        }

        @RequestMapping(method = RequestMethod.POST,value="/human/{playerId}")
        public SgfObj vsHumanPlayer(@RequestBody PlayMessage playMessage, @PathVariable String playerId){
                User user  = userRepository.findOne(playerId);
                user.setStatus(UserStatus.PLAYING.getIndex());
                userRepository.save(user);
                //update gamer
                return updateSgfObj(playMessage.getMsg(), playMessage.getGame_id());
        }
        private boolean hasHumanPlayer(Gamer gamer) {
                return
                (gamer.getPlayer1().getType() == UserTypes.HUMAN.getIndex() || gamer.getPlayer2().getType() == UserTypes.HUMAN.getIndex());
        }

        @RequestMapping(method = RequestMethod.GET, value="/q/{type}/{name}")
        public Gamer qCreateGamer(@PathVariable int type,@PathVariable String name) throws InterruptedException, DockerException, MqttException, DockerCertificateException {

                return service.createGamerByType(type,name);
        }


}
