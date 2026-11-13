//! The assistant asks the provider with the screen, keeps the conversation per server, and offers the command.

use std::io::{BufRead, BufReader, Read, Write};

use iced::futures::StreamExt;
use iced::Task;
use nautile_app::app::{App, Message};
use nautile_app::assistant::AiMsg;
use nautile_app::sessions::{Tab, TabState};
use nautile_app::storage::{Paths, Settings};
use nautile_app::term::Terminal;
use nautile_core::db::Db;
use nautile_core::model::{Host, Protocol};
use nautile_net::ai::ProviderKind;

fn drive(app: &mut App, first: Task<Message>) {
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
    rt.block_on(async {
        let mut queue = vec![first];
        while let Some(t) = queue.pop() {
            let Some(mut s) = iced_runtime::task::into_stream(t) else {
                continue;
            };
            while let Some(a) = s.next().await {
                if let iced_runtime::Action::Output(m) = a {
                    if !matches!(m, Message::ToastClear) {
                        queue.push(app.update(m));
                    }
                }
            }
        }
    });
}

/// An OpenAI-compatible server answering once; returns its URL and what it received.
fn server() -> (String, std::sync::mpsc::Receiver<String>) {
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}/v1", l.local_addr().unwrap());
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let (s, _) = l.accept().unwrap();
        let mut r = BufReader::new(s.try_clone().unwrap());
        let mut len = 0;
        loop {
            let mut line = String::new();
            r.read_line(&mut line).unwrap();
            if line.to_lowercase().starts_with("content-length:") {
                len = line[15..].trim().parse().unwrap();
            }
            if line == "\r\n" {
                break;
            }
        }
        let mut body = vec![0u8; len];
        r.read_exact(&mut body).unwrap();
        tx.send(String::from_utf8(body).unwrap()).unwrap();
        let reply = r#"{"choices":[{"message":{"content":"```bash\ndf -h /\n```\nL'espace du disque **racine**."}}]}"#;
        let mut s = s;
        write!(
            s,
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{reply}",
            reply.len()
        )
        .unwrap();
    });
    (url, rx)
}

#[test]
fn a_question_becomes_a_command() {
    nautile_core::i18n::set_english(false);
    let data = std::env::temp_dir().join(format!("nautile-ai-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&data);
    let db = Db::in_memory().unwrap();
    let id = db.store().insert(&Host::new("docker-01", "10.0.0.20")).unwrap();
    let (url, received) = server();
    let mut settings = Settings {
        ai_provider: Some(ProviderKind::Local),
        ..Settings::default()
    };
    settings.ai_urls.insert("LOCAL".into(), url);
    settings.ai_models.insert("LOCAL".into(), "llama3.1".into());
    let mut app = App::with(
        Paths {
            data: data.clone(),
            portable: true,
        },
        settings,
        Some(db),
        None,
    );
    let mut term = Terminal::new(80, 24, app.term_theme(), app.cursor_shape());
    term.feed(b"mrd@docker-01:~$ cp big.iso /\r\ncp: error writing '/big.iso': No space left on device\r\n");
    app.tabs.push(Tab {
        id: 1,
        host_id: Some(id),
        name: "docker-01".into(),
        color: 0,
        protocol: Protocol::Ssh,
        term,
        input: None,
        state: TabState::Open,
        remote_title: None,
        files: None,
        remote: None,
        log: None,
        monitor: None,
    });
    let _ = app.update(Message::SelectTab(Some(1)));
    let _ = app.update(Message::Ai(AiMsg::Toggle));
    assert!(app.ai_ready());
    let _ = app.update(Message::Ai(AiMsg::Input("pourquoi la copie échoue ?".into())));
    let t = app.update(Message::Ai(AiMsg::Send));
    drive(&mut app, t);
    assert_eq!(app.assistant.error, None);

    let sent: serde_json::Value = serde_json::from_str(&received.recv().unwrap()).unwrap();
    let user = sent["messages"][1]["content"].as_str().unwrap();
    assert!(user.contains("docker-01 (10.0.0.20, SSH)"), "{user}");
    assert!(user.contains("No space left on device"), "the screen goes along");
    assert!(user.ends_with("Demande : pourquoi la copie échoue ?"));

    let turns = app.ai_conversation();
    assert_eq!(turns.len(), 1);
    assert_eq!(turns[0].command.as_deref(), Some("df -h /"));
    assert!(turns[0].turn.screen);
    // Kept on this computer for the next time.
    let files: Vec<_> = std::fs::read_dir(data.join("ai-conversations")).unwrap().collect();
    assert_eq!(files.len(), 1);
    let _ = std::fs::remove_dir_all(&data);
}
