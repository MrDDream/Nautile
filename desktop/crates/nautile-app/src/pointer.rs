//! A wrapper that knows where the pointer is: a right click gives its position (to open a menu there),
//! and dragging (left button held and moved) starts and ends a drag, without the click reaching the content.

use iced::advanced::layout;
use iced::advanced::mouse;
use iced::advanced::overlay;
use iced::advanced::renderer;
use iced::advanced::widget::{tree, Operation, Tree};
use iced::advanced::{Clipboard, Layout, Shell, Widget};
use iced::{Element, Event, Length, Point, Rectangle, Size, Vector};

pub struct Pointer<'a, Message> {
    content: Element<'a, Message>,
    on_right: Option<Box<dyn Fn(Point) -> Message + 'a>>,
    on_drag_start: Option<Message>,
    on_drag_end: Option<Message>,
}

#[derive(Default)]
struct State {
    pressed_at: Option<Point>,
    dragging: bool,
}

/// Wraps [content].
pub fn pointer<'a, Message>(content: impl Into<Element<'a, Message>>) -> Pointer<'a, Message> {
    Pointer {
        content: content.into(),
        on_right: None,
        on_drag_start: None,
        on_drag_end: None,
    }
}

impl<'a, Message> Pointer<'a, Message> {
    /// A right click, with the pointer's place in the window.
    pub fn on_right(mut self, f: impl Fn(Point) -> Message + 'a) -> Self {
        self.on_right = Some(Box::new(f));
        self
    }

    /// The content is dragged (the button held and moved a few pixels), then let go.
    pub fn on_drag(mut self, start: Message, end: Message) -> Self {
        self.on_drag_start = Some(start);
        self.on_drag_end = Some(end);
        self
    }
}

impl<Message: Clone> Widget<Message, iced::Theme, iced::Renderer> for Pointer<'_, Message> {
    fn tag(&self) -> tree::Tag {
        tree::Tag::of::<State>()
    }

    fn state(&self) -> tree::State {
        tree::State::new(State::default())
    }

    fn children(&self) -> Vec<Tree> {
        vec![Tree::new(&self.content)]
    }

    fn diff(&self, tree: &mut Tree) {
        tree.diff_children(std::slice::from_ref(&self.content));
    }

    fn size(&self) -> Size<Length> {
        self.content.as_widget().size()
    }

    fn layout(&mut self, tree: &mut Tree, renderer: &iced::Renderer, limits: &layout::Limits) -> layout::Node {
        self.content.as_widget_mut().layout(&mut tree.children[0], renderer, limits)
    }

    fn operate(&mut self, tree: &mut Tree, layout: Layout<'_>, renderer: &iced::Renderer, operation: &mut dyn Operation) {
        self.content
            .as_widget_mut()
            .operate(&mut tree.children[0], layout, renderer, operation);
    }

    fn update(
        &mut self,
        tree: &mut Tree,
        event: &Event,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        renderer: &iced::Renderer,
        clipboard: &mut dyn Clipboard,
        shell: &mut Shell<'_, Message>,
        viewport: &Rectangle,
    ) {
        let bounds = layout.bounds();
        // The end of a drag is not a click on the content.
        if let Event::Mouse(mouse::Event::ButtonReleased(mouse::Button::Left)) = event {
            let state = tree.state.downcast_mut::<State>();
            let was = state.dragging;
            state.dragging = false;
            state.pressed_at = None;
            if was {
                if let Some(m) = &self.on_drag_end {
                    shell.publish(m.clone());
                }
                shell.capture_event();
                return;
            }
        }
        if let Event::Mouse(mouse::Event::CursorMoved { position }) = event {
            let state = tree.state.downcast_mut::<State>();
            if let (Some(at), false, Some(m)) = (state.pressed_at, state.dragging, &self.on_drag_start) {
                if at.distance(*position) > 8.0 {
                    state.dragging = true;
                    shell.publish(m.clone());
                }
            }
        }
        if let Event::Mouse(mouse::Event::ButtonPressed(mouse::Button::Left)) = event {
            let state = tree.state.downcast_mut::<State>();
            state.pressed_at = cursor.position_over(bounds);
        }
        self.content
            .as_widget_mut()
            .update(&mut tree.children[0], event, layout, cursor, renderer, clipboard, shell, viewport);
        if shell.is_event_captured() {
            return;
        }
        if let (Event::Mouse(mouse::Event::ButtonPressed(mouse::Button::Right)), Some(f)) = (event, &self.on_right) {
            if let Some(p) = cursor.position_over(bounds) {
                shell.publish(f(p));
                shell.capture_event();
            }
        }
    }

    fn mouse_interaction(
        &self,
        tree: &Tree,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        viewport: &Rectangle,
        renderer: &iced::Renderer,
    ) -> mouse::Interaction {
        if tree.state.downcast_ref::<State>().dragging {
            return mouse::Interaction::Grabbing;
        }
        self.content
            .as_widget()
            .mouse_interaction(&tree.children[0], layout, cursor, viewport, renderer)
    }

    fn draw(
        &self,
        tree: &Tree,
        renderer: &mut iced::Renderer,
        theme: &iced::Theme,
        style: &renderer::Style,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        viewport: &Rectangle,
    ) {
        self.content
            .as_widget()
            .draw(&tree.children[0], renderer, theme, style, layout, cursor, viewport);
    }

    fn overlay<'b>(
        &'b mut self,
        tree: &'b mut Tree,
        layout: Layout<'b>,
        renderer: &iced::Renderer,
        viewport: &Rectangle,
        translation: Vector,
    ) -> Option<overlay::Element<'b, Message, iced::Theme, iced::Renderer>> {
        self.content
            .as_widget_mut()
            .overlay(&mut tree.children[0], layout, renderer, viewport, translation)
    }
}

impl<'a, Message: Clone + 'a> From<Pointer<'a, Message>> for Element<'a, Message> {
    fn from(p: Pointer<'a, Message>) -> Self {
        Element::new(p)
    }
}
