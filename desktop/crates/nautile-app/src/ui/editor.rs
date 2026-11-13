//! The forms that create and edit hosts, groups, snippets, keys, identities and tunnels.

use super::*;
#[allow(unused_imports)]
use iced::widget::{button, column, container, image, pick_list, row, scrollable, stack, text, text_editor, text_input, Column, Space};

/// The panel's title, form, save action and delete action (with its « confirm » state).
pub(super) type Panel<'a> = (String, Element<'a, Message>, Option<Message>, Option<(bool, Message)>);

pub(super) fn editor<'a>(app: &'a App, e: &'a Editor, max_height: f32) -> Element<'a, Message> {
    let (heading, body, save, delete): Panel<'a> = match e {
        Editor::Host(f) => {
            let none = || Choice {
                id: None,
                label: tr("Aucun"),
            };
            let groups: Vec<Choice> = std::iter::once(Choice {
                id: None,
                label: tr("Sans groupe"),
            })
            .chain(app.groups.iter().map(|g| Choice {
                id: Some(g.id),
                label: g.name.clone(),
            }))
            .collect();
            let jumps: Vec<Choice> = std::iter::once(Choice {
                id: None,
                label: tr("Aucun — connexion directe"),
            })
            .chain(
                app.hosts
                    .iter()
                    .filter(|h| Some(h.id) != f.original.as_ref().map(|o| o.id) && matches!(h.protocol, Protocol::Ssh))
                    .map(|h| Choice {
                        id: Some(h.id),
                        label: h.alias.clone(),
                    }),
            )
            .collect();
            let keys: Vec<Choice> = std::iter::once(none())
                .chain(app.keys.iter().map(|k| Choice {
                    id: Some(k.id),
                    label: k.name.clone(),
                }))
                .collect();
            let ids: Vec<Choice> = std::iter::once(none())
                .chain(app.identities.iter().map(|i| Choice {
                    id: Some(i.id),
                    label: format!("{} ({})", i.name, i.username),
                }))
                .collect();
            let pick = |list: &[Choice], id: Option<i64>| list.iter().find(|c| c.id == id).cloned();

            let network = f.protocol != Protocol::Local;
            let select = |p: iced::widget::PickList<'a, Choice, Vec<Choice>, Choice, Message>| {
                p.width(Fill).padding([14, 14]).text_size(15).style(theme::select)
            };
            // The host as it will look in the list, with its type.
            let shown_name = if f.alias.trim().is_empty() {
                f.hostname.trim().to_owned()
            } else {
                f.alias.trim().to_owned()
            };
            let address = match (f.username.trim(), f.hostname.trim()) {
                (_, "") => tr("Adresse ou nom"),
                ("", h) => h.to_owned(),
                (u, h) => format!("{u}@{h}"),
            };
            let mut who = column![
                text(if shown_name.is_empty() {
                    tr("Nouvel hôte")
                } else {
                    shown_name.clone()
                })
                .font(BOLD)
                .size(16),
                soft(address).font(MONO).size(13)
            ]
            .spacing(3)
            .width(Fill);
            if let Some(g) = f.group.and_then(|g| app.groups.iter().find(|x| x.id == g)) {
                who = who.push(
                    row![kit::dot(argb(g.color), 8.0), label(g.name.clone())]
                        .spacing(6)
                        .align_y(Alignment::Center),
                );
            }
            let os = f.original.as_ref().and_then(|o| o.os_id.clone());
            // The kind was chosen before the form; an existing host can still change it.
            let kind: Element<Message> = if f.original.is_some() {
                pick_list(&ProtocolChoice::ALL[..], Some(ProtocolChoice(f.protocol)), |p| {
                    Message::Host(HostField::Protocol(p))
                })
                .padding([8, 12])
                .text_size(14)
                .style(theme::select)
                .into()
            } else {
                container(
                    row![
                        icon(kind_icon(f.protocol)).size(16),
                        text(crate::app::protocol_label(f.protocol)).size(13).font(BOLD)
                    ]
                    .spacing(6)
                    .align_y(Alignment::Center),
                )
                .padding([6, 10])
                .style(move |t: &iced::Theme| theme::chip(kind_tint(&theme::nt(t), f.protocol))(t))
                .into()
            };
            let head = container(
                row![kit::host_avatar(os.as_deref(), &shown_name, argb(f.color), 48.0), who, kind]
                    .spacing(14)
                    .align_y(Alignment::Center),
            )
            .padding(14)
            .style(theme::card);
            let mut form = column![head].spacing(14);
            if network {
                form = form.push(kit::section_label(tr("Adresse"))).push(
                    row![
                        kit::float_field(
                            tr("Adresse ou nom"),
                            input("10.0.0.20", &f.hostname, |v| Message::Host(HostField::Hostname(v))).font(MONO)
                        )
                        .width(Length::FillPortion(3)),
                        kit::float_field(tr("Port"), input("22", &f.port, |v| Message::Host(HostField::Port(v))).font(MONO))
                            .width(Length::FillPortion(1)),
                    ]
                    .spacing(10),
                );
                if f.protocol != Protocol::Vnc {
                    form = form.push(kit::float_field(
                        tr("Utilisateur"),
                        input("root", &f.username, |v| Message::Host(HostField::Username(v))).font(MONO),
                    ));
                }
            }
            form = form.push(kit::float_field(
                tr("Nom affiché (facultatif)"),
                input("docker-01", &f.alias, |v| Message::Host(HostField::Alias(v))),
            ));
            if network {
                form = form
                    .push(label(tr("Astuce : collez « user@adresse:port » dans Adresse pour tout remplir.")))
                    .push(kit::section_label(tr("Authentification")))
                    .push(segmented(
                        AuthChoice::ALL.iter().map(|a| (a.0, a.to_string())).collect(),
                        if f.auth == AuthMethod::Agent {
                            AuthMethod::Password
                        } else {
                            f.auth
                        },
                        |a| Message::Host(HostField::Auth(AuthChoice(a))),
                    ));
                match f.auth {
                    AuthMethod::Key => {
                        form = form.push(kit::float_box(
                            tr("Clé"),
                            select(pick_list(keys.clone(), pick(&keys, f.key), |c| Message::Host(HostField::Key(c)))),
                        ));
                        if app.keys.is_empty() {
                            form = form.push(label(tr(
                                "Trousseau vide : générez une clé dans le Trousseau ou importez un fichier.",
                            )));
                        }
                    }
                    AuthMethod::Identity => {
                        form = form.push(kit::float_box(
                            tr("Identité"),
                            select(pick_list(ids.clone(), pick(&ids, f.identity), |c| {
                                Message::Host(HostField::Identity(c))
                            })),
                        ))
                    }
                    _ => {
                        form = form.push(kit::float_field(
                            tr("Mot de passe"),
                            text_input(&tr("Demandé à la connexion si vide"), &f.password)
                                .on_input(|v| Message::Host(HostField::Password(v)))
                                .secure(true),
                        ))
                    }
                }
                form = form.push(kit::section_label(tr("Connexion")));
                if matches!(f.protocol, Protocol::Ssh | Protocol::Mosh) {
                    form = form.push(
                        column![
                            switch(tr("Mosh"), f.protocol == Protocol::Mosh, |v| Message::Host(HostField::Protocol(
                                ProtocolChoice(if v { Protocol::Mosh } else { Protocol::Ssh })
                            ))),
                            label(tr("Session résistante aux coupures réseau (mosh-server requis sur le serveur)")),
                        ]
                        .spacing(4),
                    );
                }
                if matches!(f.protocol, Protocol::Ssh | Protocol::Mosh | Protocol::Rdp | Protocol::Vnc) {
                    form = form.push(kit::float_box(
                        tr("Rebond SSH"),
                        select(pick_list(jumps.clone(), pick(&jumps, f.jump), |c| {
                            Message::Host(HostField::Jump(c))
                        })),
                    ));
                }
            }
            if f.protocol == Protocol::Rdp {
                form = form
                    .push(kit::float_field(
                        tr("Domaine (optionnel)"),
                        input("CORP", &f.rdp_domain, |v| Message::Host(HostField::RdpDomain(v))),
                    ))
                    .push(
                        row![
                            kit::float_field(
                                tr("Résolution"),
                                input("1600x900", &f.rdp_resolution, |v| Message::Host(HostField::RdpResolution(v))).font(MONO)
                            ),
                            kit::float_field(
                                tr("Échelle (%)"),
                                input("100", &f.rdp_scale, |v| Message::Host(HostField::RdpScale(v)))
                            )
                            .width(130),
                        ]
                        .spacing(10),
                    );
            }
            if !f.protocol.is_desktop() {
                form = form.push(kit::float_field(
                    tr("Commande au démarrage"),
                    input("htop", &f.startup, |v| Message::Host(HostField::Startup(v))).font(MONO),
                ));
            }
            form = form
                .push(kit::section_label(tr("Organisation")))
                .push(kit::float_box(
                    tr("Groupe"),
                    select(pick_list(groups.clone(), pick(&groups, f.group), |c| {
                        Message::Host(HostField::Group(c))
                    })),
                ))
                .push(field(tr("Couleur"), color_picker(f.color, |c| Message::Host(HostField::Color(c)))))
                .push(kit::float_field(
                    tr("Tags (séparés par des virgules)"),
                    input("prod, docker", &f.tags, |v| Message::Host(HostField::Tags(v))),
                ));
            let heading = if f.original.is_some() {
                tr("Modifier l'hôte")
            } else {
                tr("Nouvel hôte")
            };
            (
                heading,
                form.into(),
                f.valid().then_some(Message::SaveHost),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteHost)),
            )
        }
        Editor::Group(f) => {
            let form = column![
                kit::float_field(
                    tr("Nom"),
                    input("Homelab", &f.name, Message::GroupName).on_submit(Message::SaveGroup)
                ),
                field(tr("Couleur"), color_picker(f.color, Message::GroupColor)),
            ]
            .spacing(14);
            let heading = if f.original.is_some() {
                tr("Modifier le groupe")
            } else {
                tr("Nouveau groupe")
            };
            let hint: Element<Message> = if f.original.is_some() {
                soft(tr("Supprimer le groupe garde ses hôtes, sans groupe.")).into()
            } else {
                Space::new().into()
            };
            (
                heading,
                column![form, hint].spacing(14).into(),
                (!f.name.trim().is_empty()).then_some(Message::SaveGroup),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteGroup)),
            )
        }
        Editor::Key(f) => {
            let mut form = column![kit::float_field(tr("Nom"), input("laptop", &f.name, Message::KeyName))].spacing(14);
            if f.generate {
                form = form.push(field(
                    tr("Type"),
                    pick_list(nautile_net::keys::KeyAlgorithm::ALL, Some(f.algorithm), Message::KeyAlgorithm)
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select),
                ));
            } else {
                form = form.push(field(
                    tr("Clé privée"),
                    column![
                        text_editor(&f.text)
                            .placeholder("-----BEGIN OPENSSH PRIVATE KEY-----")
                            .on_action(Message::KeyText)
                            .font(MONO)
                            .size(12)
                            .height(180)
                            .padding(10),
                        secondary(tr("Choisir un fichier…"), Message::KeyPickFile),
                    ]
                    .spacing(8),
                ));
            }
            form = form.push(kit::float_field(
                if f.generate {
                    tr("Phrase de passe (recommandée)")
                } else {
                    tr("Phrase de passe")
                },
                text_input(&tr("Aucune"), &f.passphrase)
                    .on_input(Message::KeyPassphrase)
                    .on_submit(Message::SaveKey)
                    .secure(true)
                    .padding([9, 12])
                    .size(14)
                    .style(theme::input),
            ));
            if !f.generate {
                form = form.push(switch(tr("Mémoriser la phrase de passe"), f.remember, Message::KeyRemember));
            }
            if f.busy {
                form = form.push(soft(if f.generate {
                    tr("Génération…")
                } else {
                    tr("Lecture de la clé…")
                }));
            }
            if let Some(e) = &f.error {
                form = form.push(text(e.clone()).size(13).style(|t: &iced::Theme| text::Style {
                    color: Some(theme::nt(t).pink),
                }));
            }
            let ok = !f.name.trim().is_empty() && !f.busy && (f.generate || !f.text.text().trim().is_empty());
            (
                if f.generate {
                    tr("Générer une clé")
                } else {
                    tr("Importer une clé")
                },
                form.into(),
                ok.then_some(Message::SaveKey),
                None,
            )
        }
        Editor::Identity(f) => {
            let keys: Vec<Choice> = std::iter::once(Choice {
                id: None,
                label: tr("Aucune — mot de passe"),
            })
            .chain(app.keys.iter().map(|k| Choice {
                id: Some(k.id),
                label: k.name.clone(),
            }))
            .collect();
            let current = keys.iter().find(|c| c.id == f.key).cloned();
            let form = column![
                kit::float_field(tr("Nom"), input("admin", &f.name, Message::IdentityName)),
                kit::float_field(tr("Utilisateur"), input("root", &f.username, Message::IdentityUser)),
                field(
                    tr("Clé SSH"),
                    pick_list(keys, current, Message::IdentityKey)
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select)
                ),
                kit::float_field(
                    tr("Mot de passe"),
                    text_input(&tr("Demandé à la connexion si vide"), &f.password)
                        .on_input(Message::IdentityPassword)
                        .secure(true)
                        .padding([9, 12])
                        .size(14)
                        .style(theme::input)
                ),
            ]
            .spacing(14);
            let heading = if f.original.is_some() {
                f.name.clone()
            } else {
                tr("Nouvelle identité")
            };
            let ok = !f.name.trim().is_empty() && !f.username.trim().is_empty();
            (
                heading,
                form.into(),
                ok.then_some(Message::SaveIdentity),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteIdentity)),
            )
        }
        Editor::Forward(f) => {
            let hosts: Vec<Choice> = app
                .hosts
                .iter()
                .filter(|h| matches!(h.protocol, Protocol::Ssh | Protocol::Mosh))
                .map(|h| Choice {
                    id: Some(h.id),
                    label: h.alias.clone(),
                })
                .collect();
            let current = hosts.iter().find(|c| c.id == f.host).cloned();
            let explain = match f.kind {
                ForwardKind::Local => tr("Écoute sur cet ordinateur, se connecte à la destination depuis le serveur."),
                ForwardKind::Remote => tr("Écoute sur le serveur, se connecte à la destination depuis cet ordinateur."),
                ForwardKind::Dynamic => tr("Proxy SOCKS5 sur cet ordinateur : les applis qui le configurent sortent par le serveur."),
            };
            let mut form = column![
                kit::float_field(tr("Nom"), input("Jellyfin", &f.name, Message::ForwardName)),
                segmented(
                    vec![
                        (ForwardKind::Local, tr("Locale")),
                        (ForwardKind::Remote, tr("Distante")),
                        (ForwardKind::Dynamic, "SOCKS".into())
                    ],
                    f.kind,
                    Message::ForwardKind
                ),
                soft(explain),
                field(
                    tr("Via l'hôte"),
                    pick_list(hosts, current, Message::ForwardHost)
                        .placeholder(tr("Choisir…"))
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select)
                ),
                row![
                    kit::float_field(
                        if f.kind == ForwardKind::Remote {
                            tr("Écoute (serveur)")
                        } else {
                            tr("Écoute")
                        },
                        input("127.0.0.1", &f.bind_host, Message::ForwardBindHost).font(MONO)
                    )
                    .width(Length::FillPortion(2)),
                    kit::float_field(tr("Port"), input("8080", &f.bind_port, Message::ForwardBindPort).font(MONO))
                        .width(Length::FillPortion(1)),
                ]
                .spacing(10),
            ]
            .spacing(14);
            if f.kind != ForwardKind::Dynamic {
                form = form.push(
                    row![
                        kit::float_field(
                            "Destination".into(),
                            input("127.0.0.1", &f.dest_host, Message::ForwardDestHost).font(MONO)
                        )
                        .width(Length::FillPortion(2)),
                        kit::float_field(tr("Port"), input("80", &f.dest_port, Message::ForwardDestPort).font(MONO))
                            .width(Length::FillPortion(1)),
                    ]
                    .spacing(10),
                );
            }
            form = form.push(switch(
                tr("Démarrer avec chaque session sur cet hôte"),
                f.auto_start,
                Message::ForwardAuto,
            ));
            let heading = if f.original.is_some() {
                tr("Modifier la règle")
            } else {
                tr("Nouvelle règle")
            };
            (
                heading,
                form.into(),
                f.valid().then_some(Message::SaveForward),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteForward)),
            )
        }
        Editor::Snippet(f) => {
            let form = column![
                kit::float_field(tr("Nom"), input(&tr("Mettre à jour la stack"), &f.name, Message::SnippetName)),
                field(
                    tr("Commande"),
                    text_editor(&f.command)
                        .on_action(Message::SnippetCommand)
                        .font(MONO)
                        .size(13)
                        .height(180)
                        .padding(12)
                        .style(|t: &iced::Theme, s| {
                            let n = theme::nt(t);
                            let focused = matches!(s, text_editor::Status::Focused { .. });
                            text_editor::Style {
                                background: n.bg.into(),
                                border: iced::Border {
                                    color: if focused { n.accent } else { n.border },
                                    width: 1.0,
                                    radius: 10.0.into(),
                                },
                                placeholder: n.placeholder,
                                value: n.text,
                                selection: theme::alpha(n.accent, 0.35),
                            }
                        })
                ),
                insert_keys(),
                snippet_vars(&f.command.text()),
                kit::float_field(tr("Catégorie"), input("Docker", &f.tag, Message::SnippetTag)),
                tag_chips(app, &f.tag),
            ]
            .spacing(14);
            let heading = if f.original.is_some() {
                f.name.clone()
            } else {
                tr("Nouveau snippet")
            };
            let ok = !f.name.trim().is_empty() && !f.command.text().trim().is_empty();
            (
                heading,
                form.into(),
                ok.then_some(Message::SaveSnippet),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteSnippet)),
            )
        }
    };

    let mut actions = row![].spacing(10).align_y(Alignment::Center);
    if let Some((confirm, m)) = delete {
        actions = actions.push(
            button(text(if confirm { tr("Confirmer la suppression") } else { tr("Supprimer") }).size(14))
                .padding([9, 14])
                .style(theme::danger)
                .on_press(m),
        );
    }
    actions = actions.push(Space::new().width(Fill));
    let big = |b: button::Button<'a, Message>| b.padding([13, 22]);
    if let Editor::Host(f) = e {
        actions = actions
            .push(big(button(text(tr("Enregistrer")).size(15).font(BOLD))
                .style(theme::secondary)
                .on_press_maybe(save)))
            .push(big(button(text(tr("Connecter")).size(15).font(BOLD))
                .style(theme::primary)
                .on_press_maybe(f.valid().then_some(Message::SaveHostAndConnect))));
    } else {
        actions = actions
            .push(big(button(text(tr("Annuler")).size(15))
                .style(theme::secondary)
                .on_press(Message::CloseEditor)))
            .push(big(button(text(tr("Enregistrer")).size(15).font(BOLD))
                .style(theme::primary)
                .on_press_maybe(save)));
    }

    let in_groups = app.modal == Some(crate::app::Modal::Groups);
    let close = if app.modal.is_some() {
        Message::OpenModal(None)
    } else {
        Message::CloseEditor
    };
    let width = match e {
        Editor::Host(_) | Editor::Snippet(_) => 660.0,
        _ => 560.0,
    };
    kit::dialog(
        kit::dialog_head(heading, None, in_groups.then_some(Message::CloseEditor), close),
        body,
        Some(actions.into()),
        width,
        max_height,
    )
}

/// The icon of a kind of host.
pub(super) fn kind_icon(p: Protocol) -> char {
    match p {
        Protocol::Rdp => I_DESKTOP,
        Protocol::Vnc => I_SCREEN_SHARE,
        Protocol::Telnet => I_LAN,
        Protocol::Local => I_COMPUTER,
        Protocol::Serial => I_USB,
        Protocol::Ssh | Protocol::Mosh => I_TERMINAL,
    }
}

/// The colour of a kind of host (as in Android's « Nouvel hôte »).
pub(super) fn kind_tint(n: &theme::Nt, p: Protocol) -> Color {
    match p {
        Protocol::Rdp => n.green,
        Protocol::Vnc | Protocol::Ssh | Protocol::Mosh => n.blue,
        Protocol::Telnet => n.accent,
        Protocol::Serial => n.pink,
        Protocol::Local => n.text_soft,
    }
}

/// « Nouvel hôte »: which kind of machine, then its form.
pub(super) fn new_host_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    let n = theme::nt(&app.theme());
    let kind =
        |p: Protocol, title: String, detail: String| kit::kind_row(kind_icon(p), kind_tint(&n, p), title, detail, Message::NewHostOf(p));
    let body = column![
        kind(
            Protocol::Ssh,
            "SSH".into(),
            tr("Terminal sur un serveur Linux, macOS, routeur… (Mosh en option)")
        ),
        kind(Protocol::Rdp, tr("Bureau à distance (RDP)"), tr("Bureau Windows ou serveur xrdp")),
        kind(
            Protocol::Vnc,
            tr("Bureau à distance (VNC)"),
            tr("Linux, macOS, Raspberry Pi… (x11vnc, TigerVNC, partage d'écran)")
        ),
        kind(Protocol::Telnet, "Telnet".into(), tr("Équipements réseau anciens, consoles")),
        kind(
            Protocol::Local,
            tr("Terminal local"),
            tr("Le shell de cet ordinateur (PowerShell, bash…)")
        ),
    ]
    .spacing(2);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(
                tr("Nouvel hôte"),
                Some(tr("Quel type de machine ?")),
                None,
                Message::OpenModal(None),
            ),
            body,
            None,
            520.0,
            max_height,
        ),
        Some(Message::OpenModal(None)),
    )
}

/// The groups: their order and how many hosts each holds, then the editor of one.
pub(super) fn groups_modal<'a>(app: &'a App, editing: Option<&'a Editor>, max_height: f32) -> Element<'a, Message> {
    if let Some(e @ Editor::Group(_)) = editing {
        return kit::overlay(editor(app, e, max_height), None);
    }
    let count = |id: i64| match app.hosts.iter().filter(|h| h.group_id == Some(id)).count() {
        0 => tr("Vide"),
        1 => tr("1 hôte"),
        n => trf("%1$s hôtes", &[&n]),
    };
    let last = app.groups.len().saturating_sub(1);
    let rows: Vec<Element<Message>> = app
        .groups
        .iter()
        .enumerate()
        .map(|(i, g)| {
            let col = argb(g.color);
            // Only the moves that are possible (none up for the first, none down for the last).
            let arrow = |c: char, on: Option<Message>| -> Element<Message> {
                match on {
                    Some(m) => button(icon(c).size(20)).padding(6).style(theme::ghost).on_press(m).into(),
                    None => Space::new().width(32).into(),
                }
            };
            row![
                button(
                    row![
                        container(kit::dot(col, 12.0))
                            .center(Length::Fixed(36.0))
                            .style(theme::soft_tile(col, 10.0)),
                        column![text(g.name.clone()).size(15).font(BOLD), soft(count(g.id))]
                            .spacing(2)
                            .width(Fill),
                    ]
                    .spacing(12)
                    .align_y(Alignment::Center)
                )
                .width(Fill)
                .padding([10, 12])
                .style(theme::card_pick(false))
                .on_press(Message::EditGroup(g.id)),
                arrow(I_UP_ARROW, (i > 0).then_some(Message::MoveGroup(g.id, true))),
                arrow(I_DOWN_ARROW, (i < last).then_some(Message::MoveGroup(g.id, false))),
            ]
            .spacing(2)
            .padding(Padding {
                right: 8.0,
                ..Padding::ZERO
            })
            .align_y(Alignment::Center)
            .into()
        })
        .collect();
    let mut body = column![].spacing(14);
    if rows.is_empty() {
        body = body.push(soft(tr(
            "Aucun groupe pour l'instant : rangez vos hôtes par lieu, client ou usage.",
        )));
    } else {
        body = body.push(kit::card(rows));
    }
    let foot = row![
        Space::new().width(Fill),
        button(
            row![icon(I_ADD).size(18), text(tr("Nouveau groupe")).size(15).font(BOLD)]
                .spacing(8)
                .align_y(Alignment::Center)
        )
        .padding([11, 18])
        .style(theme::primary)
        .on_press(Message::NewGroup)
    ];
    kit::overlay(
        kit::dialog(
            kit::dialog_head(
                tr("Groupes"),
                Some(tr("Touchez un groupe pour le modifier")),
                None,
                Message::OpenModal(None),
            ),
            body,
            Some(foot.into()),
            520.0,
            max_height,
        ),
        Some(Message::OpenModal(None)),
    )
}

/// The keys that type into the command, as on the phone.
fn insert_keys<'a>() -> Element<'a, Message> {
    let key = |shown: &str, typed: &str, accent: bool| -> Element<'a, Message> {
        button(text(shown.to_owned()).font(if accent { BOLD } else { MONO }).size(14))
            .padding([7, 12])
            .style(if accent { theme::tonal } else { theme::secondary })
            .on_press(Message::SnippetInsert(typed.to_owned()))
            .into()
    };
    let mut r = row![key(&tr("+ Variable"), "${NOM}", true)].spacing(6);
    for k in ["|", "&&", ">", "~/", "sudo ", "\"", "'", "$", "\\"] {
        r = r.push(key(k.trim_end(), k, false));
    }
    r.wrap().into()
}

/// The variables found in the command, asked at each run.
fn snippet_vars<'a>(command: &str) -> Element<'a, Message> {
    let vars = nautile_core::model::snippet_variables(command);
    if vars.is_empty() {
        return soft(tr(
            "Astuce : ${NOM} devient une valeur demandée à chaque exécution. Plusieurs lignes = plusieurs commandes.",
        ))
        .into();
    }
    let mut r = row![label(tr("Demandées à chaque exécution"))]
        .spacing(6)
        .align_y(Alignment::Center);
    for v in vars {
        r = r.push(
            container(text(format!("${{{v}}}")).font(MONO).size(12))
                .padding([3, 8])
                .style(|t: &iced::Theme| theme::chip(theme::nt(t).accent)(t)),
        );
    }
    r.wrap().into()
}

/// The categories already used, to pick one.
fn tag_chips<'a>(app: &'a App, current: &str) -> Element<'a, Message> {
    let mut tags: Vec<&str> = app.snippets.iter().map(|s| s.tag.as_str()).filter(|t| !t.is_empty()).collect();
    tags.sort_unstable();
    tags.dedup();
    let mut r = row![].spacing(6);
    for t in tags {
        let selected = t == current.trim();
        r = r.push(
            button(text(t.to_owned()).size(13))
                .padding([5, 12])
                .style(theme::count_chip(selected, None))
                .on_press(Message::SnippetTag(t.to_owned())),
        );
    }
    r.wrap().into()
}
