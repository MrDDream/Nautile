//! The dimmed veil behind a pop-up, drawn in tiles.
//!
//! The CPU renderer (tiny-skia) blends a translucent shape over its whole area each time any part of it is
//! repainted, even a button's hover: one quad over the window cost 12 ms at 1920×1080 (25 ms and more on a
//! larger screen) on every redraw. Tiles keep the same look, and a repaint only blends the few it touches.

use iced::advanced::layout;
use iced::advanced::renderer::{self, Quad};
use iced::advanced::widget::Tree;
use iced::advanced::{Layout, Widget, mouse};
use iced::{Color, Element, Length, Rectangle, Size};

/// Side of a tile, in logical pixels: a whole number of physical pixels at the usual scales (100 to 200 %),
/// so the tiles meet without a line where they would overlap.
const TILE: f32 = 64.0;

pub struct Backdrop {
    color: Color,
}

/// A veil of [color] filling the space it is given.
pub fn backdrop<'a, Message: 'a>(color: Color) -> Element<'a, Message> {
    Element::new(Backdrop { color })
}

impl<Message> Widget<Message, iced::Theme, iced::Renderer> for Backdrop {
    fn size(&self) -> Size<Length> {
        Size::new(Length::Fill, Length::Fill)
    }

    fn layout(&mut self, _tree: &mut Tree, _renderer: &iced::Renderer, limits: &layout::Limits) -> layout::Node {
        layout::Node::new(limits.max())
    }

    fn draw(
        &self,
        _tree: &Tree,
        renderer: &mut iced::Renderer,
        _theme: &iced::Theme,
        _style: &renderer::Style,
        layout: Layout<'_>,
        _cursor: mouse::Cursor,
        _viewport: &Rectangle,
    ) {
        use iced::advanced::Renderer as _;
        for tile in tiles(layout.bounds()) {
            renderer.fill_quad(
                Quad {
                    bounds: tile,
                    ..Quad::default()
                },
                self.color,
            );
        }
    }
}

/// [bounds] cut in [TILE] squares, the last ones of each row and column shorter.
fn tiles(bounds: Rectangle) -> impl Iterator<Item = Rectangle> {
    let cols = (bounds.width / TILE).ceil().max(0.0) as usize;
    let rows = (bounds.height / TILE).ceil().max(0.0) as usize;
    (0..rows).flat_map(move |r| {
        (0..cols).map(move |c| {
            let (x, y) = (c as f32 * TILE, r as f32 * TILE);
            Rectangle {
                x: bounds.x + x,
                y: bounds.y + y,
                width: TILE.min(bounds.width - x),
                height: TILE.min(bounds.height - y),
            }
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tiles_cover_the_area_once() {
        let b = Rectangle {
            x: 0.0,
            y: 0.0,
            width: 1000.0,
            height: 300.0,
        };
        let t: Vec<Rectangle> = tiles(b).collect();
        assert_eq!(t.len(), 16 * 5);
        let area: f32 = t.iter().map(|r| r.width * r.height).sum();
        assert_eq!(area, 1000.0 * 300.0);
        assert!(t.iter().all(|r| r.x + r.width <= 1000.0 && r.y + r.height <= 300.0));
    }
}
