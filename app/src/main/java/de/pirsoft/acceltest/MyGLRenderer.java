package de.pirsoft.acceltest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

class MyGLRenderer implements GLSurfaceView.Renderer {

	private static int loadShader(int type, String shaderCode){

		// create a vertex shader type (GLES20.GL_VERTEX_SHADER)
		// or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
		int shader = GLES20.glCreateShader(type);

		// add the source code to the shader and compile it
		GLES20.glShaderSource(shader, shaderCode);
		GLES20.glCompileShader(shader);

		return shader;
	}

	private final String vertexShaderCode =
		// This matrix member variable provides a hook to manipulate
		// the coordinates of the objects that use this vertex shader
		"uniform mat4 uMVPMatrix;" +
			"attribute vec4 aPosition;" +
			"attribute vec2 aTexCoord;" +
			"varying vec2 vTexCoord;" +
			"void main() {" +
			// the matrix must be included as a modifier of gl_Position
			// Note that the uMVPMatrix factor *must be first* in order
			// for the matrix multiplication product to be correct.
			"  gl_Position = uMVPMatrix * aPosition;" +
			"  vTexCoord = aTexCoord;" +
			"}";

	private final String fragmentShaderCode =
		"precision mediump float;" +
			"uniform sampler2D uTexture;" +
			"varying vec2 vTexCoord;" +
			"void main() {" +
			"  gl_FragColor = texture2D(uTexture, vTexCoord);" +
			"}";

	private class Sphere {

		private FloatBuffer floatBuffer;
		private ShortBuffer drawListBuffer;

		// Set color with red, green, blue and alpha (opacity) values
		float color[] = { 0.63671875f, 0.76953125f, 0.22265625f, 1.0f };

		//x,y,z(position coordinates),  s,t(texture coordinates)
		private final int FLOATSTRIDE = 5;
		private float floatAttribs[];
		private short drawOrder[];

		private final int mProgram;
		private final int maPositionHandle;
		private final int maTexCoordHandle;
		private final int muMVPMatrixHandle;
		private final int muTextureHandle;
		private final int idxbo[] = new int[1];
		private final int vtxbo[] = new int[1];
		private final int texHandle[] = new int[1];

		private void genVerticesAndIndexes(int subdivide) {
			/* Plan: base is a cube, corners at sqrt(1/3),sqrt(1/3),sqrt(1/3), then subdivided along the edges.
			 * Then, all vertices get normalized, so they have a distance of 1 from the
			 * center. Due to normalisation, we can also use 1,1,1 as corners.
			 *
			 * The Texture is a classic cube map:
			 *         +------+
			 *         | 0 1 0|
			 *         |      |
			 *  +------+------+------+------+
			 *  |-1 0 0| 0 0 1| 1 0 0|0 0 -1|
			 *  |      |      |      |      |
			 *  +------+------+------+------+
			 *         |0 -1 0|
			 *         |      |
			 *         +------+
			 *
			 * we use quad strips.
			 *
			 * vertex numbering:
			 * we start with the eight corners, 14 when mapped onto the texture:
			 *         0      1
			 *
			 *
			 *  2      3      4      5      6
			 *
			 *
			 *  7      8      9     10     11
			 *
			 *
			 *        12     13
			 *
			 *  Then, the 12 edges, 19 when mapped onto the texture:
			 *         +-----------------------------------------------------------------------------------+
			 *         |                                                                                   |
			 *         |                    0-  14+n*0..13+n*1  -1                                         |
			 *         |                    |                    |                                         |
			 *         |              14+n*1..13+n*2       14+n*2..13+n*3                                  |
			 *         |                    |                    |                                         |
			 *         2-  14+n*3..13+n*4  -3-  14+n*4..13+n*5  -4-  14+n*5..13+n*6  -5-  14+n*6..13+n*7  -6
			 *         |                    |                    |                    |                    |
			 *  14+n*7..13+n*8        14+n*8..13+n*9       14+n*9..13+n*10     14+n*10..13+n*11     14+n*11..13+n*12
			 *         |                    |                    |                    |                    |
			 *         7- 14+n*12..13+n*13 -8- 14+n*13..13+n*14 -9- 14+n*14..13+n*15 10- 14+n*15..13+n*16 11
			 *         |                    |                    |                                         |
			 *         |             14+n*16..13+n*17    14+n*17..13+n*18                                  |
			 *         |                    |                    |                                         |
			 *         |                    12 14+n*18..13+n*19 13                                         |
                         *         |                                                                                   |
                         *         +-----------------------------------------------------------------------------------+
			 *
			 *  And finally, 6 faces, indexed, with starting point:
			 *      *  *
			 *       `-
			 *        0
			 *   *  *  *  *  *
			 *    `- `- `- `-
			 *     1  2  3  4
			 *   *  *  *  *  *
			 *       `-
			 *        5
			 *      *  *
			 *
			 *  Each face is filled linewise:
			 *
			 *     14+n*19+f*n*n+y*n+x
			 *
			 * So, for the 6 Faces(looking from outside), we have:
			 * Face#|CW corner coord|CW corners|CW tex coord|CW edge start|CW edge end
			 *  0   | -1  1 -1      | 0        | 1/4 1/8    | 14+n*0      | 13+n*1
			 *      |  1  1 -1      | 1        | 2/4 1/8    | 14+n*2      | 13+n*3
			 *      |  1  1  1      | 4        | 2/4 3/8    | 13+n*5      | 14+n*4
			 *      | -1  1  1      | 3        | 1/4 3/8    | 13+n*2      | 14+n*1
			 * -----+---------------+----------+------------+-------------+-----------
			 *  1   | -1  1 -1      | 2        | 0/4 3/8    | 14+n*3      | 13+n*4
			 *      | -1  1  1      | 3        | 1/4 3/8    | 14+n*8      | 13+n*9
			 *      | -1 -1  1      | 8        | 1/4 5/8    | 13+n*13     | 14+n*12
			 *      | -1 -1 -1      | 7        | 0/4 5/8    | 13+n*8      | 14+n*7
			 * -----+---------------+----------+------------+-------------+-----------
			 *  2   | -1  1  1      | 3        | 1/4 3/8    | 14+n*4      | 13+n*5
			 *      |  1  1  1      | 4        | 2/4 3/8    | 14+n*9      | 13+n*10
			 *      |  1 -1  1      | 9        | 2/4 5/8    | 13+n*14     | 14+n*13
			 *      | -1 -1  1      | 8        | 1/4 5/8    | 13+n*9      | 14+n*8
			 * -----+---------------+----------+------------+-------------+-----------
			 *  3   |  1  1  1      | 4        | 2/4 3/8    | 14+n*5      | 13+n*6
			 *      |  1  1 -1      | 5        | 3/4 3/8    | 14+n*10     | 13+n*11
			 *      |  1 -1 -1      | 10       | 3/4 5/8    | 13+n*15     | 14+n*14
			 *      |  1 -1  1      | 9        | 2/4 5/8    | 13+n*10     | 14+n*9
			 * -----+---------------+----------+------------+-------------+-----------
			 *  4   |  1  1 -1      | 5        | 3/4 3/8    | 14+n*6      | 13+n*7
			 *      | -1  1 -1      | 6        | 4/4 3/8    | 14+n*11     | 13+n*12
			 *      | -1 -1 -1      | 11       | 4/4 5/8    | 13+n*16     | 14+n*15
			 *      |  1 -1 -1      | 10       | 3/4 5/8    | 13+n*11     | 14+n*10
			 * -----+---------------+----------+------------+-------------+-----------
			 *  5   | -1 -1  1      | 8        | 1/4 5/8    | 14+n*13     | 13+n*14
			 *      |  1 -1  1      | 9        | 2/4 5/8    | 14+n*17     | 13+n*18
			 *      |  1 -1 -1      | 13       | 2/4 7/8    | 13+n*19     | 14+n*18
			 *      | -1 -1 -1      | 12       | 1/4 7/8    | 13+n*17     | 14+n*16
			 *
			 *  Plus the inner vertex indexes as above.
			 *  Need to also generate the draw commands for these.
			 *
			 *  For the 19 Edges:
			 * Edge#|corner coord|corners|tex coord|edge idx
			 *  0   | -1  1 -1   | 0     | 1/4 1/8 | 14+n*0
			 *      |  1  1 -1   | 1     | 2/4 1/8 | 13+n*1
			 * -----+------------+-------+---------+---------
			 * -----+------------+-------+---------+---------
			 *  1   | -1  1 -1   | 0     | 1/4 1/8 | 14+n*1
			 *      | -1  1  1   | 3     | 1/4 3/8 | 13+n*2
			 * -----+------------+-------+---------+---------
			 *  2   |  1  1 -1   | 1     | 2/4 1/8 | 14+n*2
			 *      |  1  1  1   | 4     | 2/4 3/8 | 13+n*3
			 * -----+------------+-------+---------+---------
			 * -----+------------+-------+---------+---------
			 *  3   | -1  1 -1   | 2     | 0/4 3/8 | 14+n*3
			 *      | -1  1  1   | 3     | 1/4 3/8 | 13+n*4
			 * -----+------------+-------+---------+---------
			 *  4   | -1  1  1   | 3     | 1/4 3/8 | 14+n*4
			 *      |  1  1  1   | 4     | 2/4 3/8 | 13+n*5
			 * -----+------------+-------+---------+---------
			 *  5   |  1  1  1   | 4     | 2/4 3/8 | 14+n*5
			 *      |  1  1 -1   | 5     | 3/4 3/8 | 13+n*6
			 * -----+------------+-------+---------+---------
			 *  6   |  1  1 -1   | 5     | 3/4 3/8 | 14+n*6
			 *      | -1  1 -1   | 6     | 4/4 3/8 | 13+n*7
			 * -----+------------+-------+---------+---------
			 * -----+------------+-------+---------+---------
			 *  7   | -1  1 -1   | 2     | 0/4 3/8 | 14+n*7
			 *      | -1 -1 -1   | 7     | 0/4 5/8 | 13+n*8
			 * -----+------------+-------+---------+---------
			 *  8   | -1  1  1   | 3     | 1/4 3/8 | 14+n*8
			 *      | -1 -1  1   | 8     | 1/4 5/8 | 13+n*9
			 * -----+------------+-------+---------+---------
			 *  9   |  1  1  1   | 4     | 2/4 3/8 | 14+n*9
			 *      |  1 -1  1   | 9     | 2/4 5/8 | 13+n*10
			 * -----+------------+-------+---------+---------
			 *  10  |  1  1 -1   | 5     | 3/4 3/8 | 14+n*10
			 *      |  1 -1 -1   | 10    | 3/4 5/8 | 13+n*11
			 * -----+------------+-------+---------+---------
			 *  11  | -1  1 -1   | 6     | 4/4 3/8 | 14+n*11
			 *      | -1 -1 -1   | 11    | 4/4 5/8 | 13+n*12
			 * -----+------------+-------+---------+---------
			 * -----+------------+-------+---------+---------
			 *  12  | -1 -1 -1   | 7     | 0/4 5/8 | 14+n*12
			 *      | -1 -1  1   | 8     | 1/4 5/8 | 13+n*13
			 * -----+------------+-------+---------+---------
			 *  13  | -1 -1  1   | 8     | 1/4 5/8 | 14+n*13
			 *      |  1 -1  1   | 9     | 2/4 5/8 | 13+n*14
			 * -----+------------+-------+---------+---------
			 *  14  |  1 -1  1   | 9     | 2/4 5/8 | 14+n*14
			 *      |  1 -1 -1   | 10    | 3/4 5/8 | 13+n*15
			 * -----+------------+-------+---------+---------
			 *  15  |  1 -1 -1   | 10    | 3/4 5/8 | 14+n*15
			 *      | -1 -1 -1   | 11    | 4/4 5/8 | 13+n*16
			 * -----+------------+-------+---------+---------
			 * -----+------------+-------+---------+---------
			 *  16  | -1 -1  1   | 8     | 1/4 5/8 | 14+n*16
			 *      | -1 -1 -1   | 12    | 1/4 7/8 | 13+n*17
			 * -----+------------+-------+---------+---------
			 *  17  |  1 -1  1   | 9     | 2/4 5/8 | 14+n*17
			 *      |  1 -1 -1   | 13    | 2/4 7/8 | 13+n*18
			 * -----+------------+-------+---------+---------
			 * -----+------------+-------+---------+---------
			 *  18  | -1 -1 -1   | 12    | 1/4 7/8 | 14+n*18
			 *      |  1 -1 -1   | 13    | 2/4 7/8 | 13+n*19
			 */

			floatAttribs = new float[(14 + subdivide*19 + subdivide*subdivide * 6)*FLOATSTRIDE];
			// 14 Corners vertices:
			//       Idx|corner coord|tex coord
			genVertex( 0, -1,  1, -1, 1.f/4, 1.f/8);
			genVertex( 1,  1,  1, -1, 2.f/4, 1.f/8);
			genVertex( 2, -1,  1, -1, 0.f/4, 3.f/8);
			genVertex( 3, -1,  1,  1, 1.f/4, 3.f/8);
			genVertex( 4,  1,  1,  1, 2.f/4, 3.f/8);
			genVertex( 5,  1,  1, -1, 3.f/4, 3.f/8);
			genVertex( 6, -1,  1, -1, 4.f/4, 3.f/8);
			genVertex( 7, -1, -1, -1, 0.f/4, 5.f/8);
			genVertex( 8, -1, -1,  1, 1.f/4, 5.f/8);
			genVertex( 9,  1, -1,  1, 2.f/4, 5.f/8);
			genVertex(10,  1, -1, -1, 3.f/4, 5.f/8);
			genVertex(11, -1, -1, -1, 4.f/4, 5.f/8);
			genVertex(12, -1, -1, -1, 1.f/4, 7.f/8);
			genVertex(13,  1, -1, -1, 2.f/4, 7.f/8);

			// 19 Edges:
			//  |corner coord|tex coord|edge idx         |Edge#|corner
			genEdge(-1, 1,-1, 1.f/4, 1.f/8, 14+subdivide*0,  //#0  | 0
				 1, 1,-1, 2.f/4, 1.f/8, 13+subdivide*1); //    | 1

			genEdge(-1, 1,-1, 1.f/4, 1.f/8, 14+subdivide*1,  //#1  | 0
				-1, 1, 1, 1.f/4, 3.f/8, 13+subdivide*2); //    | 3
			genEdge( 1, 1,-1, 2.f/4, 1.f/8, 14+subdivide*2,  //#2  | 1
				 1, 1, 1, 2.f/4, 3.f/8, 13+subdivide*3); //    | 4

			genEdge(-1, 1,-1, 0.f/4, 3.f/8, 14+subdivide*3,  //#3  | 2
				-1, 1, 1, 1.f/4, 3.f/8, 13+subdivide*4); //    | 3
			genEdge(-1, 1, 1, 1.f/4, 3.f/8, 14+subdivide*4,  //#4  | 3
				 1, 1, 1, 2.f/4, 3.f/8, 13+subdivide*5); //    | 4
			genEdge( 1, 1, 1, 2.f/4, 3.f/8, 14+subdivide*5,  //#5  | 4
				 1, 1,-1, 3.f/4, 3.f/8, 13+subdivide*6); //    | 5
			genEdge( 1, 1,-1, 3.f/4, 3.f/8, 14+subdivide*6,  //#6  | 5
				-1, 1,-1, 4.f/4, 3.f/8, 13+subdivide*7); //    | 6

			genEdge(-1, 1,-1, 0.f/4, 3.f/8, 14+subdivide*7,  //#7  | 2
				-1,-1,-1, 0.f/4, 5.f/8, 13+subdivide*8); //    | 7
			genEdge(-1, 1, 1, 1.f/4, 3.f/8, 14+subdivide*8,  //#8  | 3
				-1,-1, 1, 1.f/4, 5.f/8, 13+subdivide*9); //    | 8
			genEdge( 1, 1, 1, 2.f/4, 3.f/8, 14+subdivide*9,  //#9  | 4
				 1,-1, 1, 2.f/4, 5.f/8, 13+subdivide*10);//    | 9
			genEdge( 1, 1,-1, 3.f/4, 3.f/8, 14+subdivide*10, //#10 | 5
				 1,-1,-1, 3.f/4, 5.f/8, 13+subdivide*11);//    | 10
			genEdge(-1, 1,-1, 4.f/4, 3.f/8, 14+subdivide*11, //#11 | 6
				-1,-1,-1, 4.f/4, 5.f/8, 13+subdivide*12);//    | 11

			genEdge(-1,-1,-1, 0.f/4, 5.f/8, 14+subdivide*12, //#12 | 7
				-1,-1, 1, 1.f/4, 5.f/8, 13+subdivide*13);//    | 8
			genEdge(-1,-1, 1, 1.f/4, 5.f/8, 14+subdivide*13, //#13 | 8
				 1,-1, 1, 2.f/4, 5.f/8, 13+subdivide*14);//    | 9
			genEdge( 1,-1, 1, 2.f/4, 5.f/8, 14+subdivide*14, //#14 | 9
				 1,-1,-1, 3.f/4, 5.f/8, 13+subdivide*15);//    | 10
			genEdge( 1,-1,-1, 3.f/4, 5.f/8, 14+subdivide*15, //#15 | 10
				-1,-1,-1, 4.f/4, 5.f/8, 13+subdivide*16);//    | 11

			genEdge(-1,-1, 1, 1.f/4, 5.f/8, 14+subdivide*16, //#16 | 8
				-1,-1,-1, 1.f/4, 7.f/8, 13+subdivide*17);//    | 12
			genEdge( 1,-1, 1, 2.f/4, 5.f/8, 14+subdivide*17, //#17 | 9
				 1,-1,-1, 2.f/4, 7.f/8, 13+subdivide*18);//    | 13

			genEdge(-1,-1,-1, 1.f/4, 7.f/8, 14+subdivide*18, //#18 | 12
				 1,-1,-1, 2.f/4, 7.f/8, 13+subdivide*19);//    | 13

			int faceidxcnt = 6+8*subdivide+2*subdivide*subdivide;
			drawOrder = new short[faceidxcnt*6-2];
			// So, for the 6 Faces(looking from outside), we have:
			// CW corner coord|-idx|CW tex coord|CW edge start  |CW edge end    |Face#
			genFace(subdivide, 14+subdivide*19+    0*subdivide*subdivide, 0,    //#0
				-1, 1,-1,  0,   1.f/4,1.f/8, 14+subdivide*0, 13+subdivide*1,
				 1, 1,-1,  1,   2.f/4,1.f/8, 14+subdivide*2, 13+subdivide*3,
				 1, 1, 1,  4,   2.f/4,3.f/8, 13+subdivide*5, 14+subdivide*4,
				-1, 1, 1,  3,   1.f/4,3.f/8, 13+subdivide*2, 14+subdivide*1);
			genFace(subdivide, 14+subdivide*19+    1*subdivide*subdivide, faceidxcnt-1,    //#1
				-1, 1,-1,  2,   0.f/4,3.f/8, 14+subdivide*3, 13+subdivide*4,
				-1, 1, 1,  3,   1.f/4,3.f/8, 14+subdivide*8, 13+subdivide*9,
				-1,-1, 1,  8,   1.f/4,5.f/8, 13+subdivide*13,14+subdivide*12,
				-1,-1,-1,  7,   0.f/4,5.f/8, 13+subdivide*8, 14+subdivide*7);
			genFace(subdivide, 14+subdivide*19+    2*subdivide*subdivide, 2*faceidxcnt-1,    //#2
				-1, 1, 1,  3,   1.f/4,3.f/8, 14+subdivide*4, 13+subdivide*5,
				 1, 1, 1,  4,   2.f/4,3.f/8, 14+subdivide*9, 13+subdivide*10,
				 1,-1, 1,  9,   2.f/4,5.f/8, 13+subdivide*14,14+subdivide*13,
				-1,-1, 1,  8,   1.f/4,5.f/8, 13+subdivide*9, 14+subdivide*8);
			genFace(subdivide, 14+subdivide*19+    3*subdivide*subdivide, 3*faceidxcnt-1,    //#3
				 1, 1, 1,  4,   2.f/4,3.f/8, 14+subdivide*5, 13+subdivide*6,
				 1, 1,-1,  5,   3.f/4,3.f/8, 14+subdivide*10,13+subdivide*11,
				 1,-1,-1,  10,  3.f/4,5.f/8, 13+subdivide*15,14+subdivide*14,
				 1,-1, 1,  9,   2.f/4,5.f/8, 13+subdivide*10,14+subdivide*9);
			genFace(subdivide, 14+subdivide*19+    4*subdivide*subdivide, 4*faceidxcnt-1,    //#4
				 1, 1,-1,  5,   3.f/4,3.f/8, 14+subdivide*6, 13+subdivide*7,
				-1, 1,-1,  6,   4.f/4,3.f/8, 14+subdivide*11,13+subdivide*12,
				-1,-1,-1,  11,  4.f/4,5.f/8, 13+subdivide*16,14+subdivide*15,
				 1,-1,-1,  10,  3.f/4,5.f/8, 13+subdivide*11,14+subdivide*10);
			genFace(subdivide, 14+subdivide*19+    5*subdivide*subdivide, 5*faceidxcnt-1,   //#5
				-1,-1, 1,  8,   1.f/4,5.f/8, 14+subdivide*13,13+subdivide*14,
				 1,-1, 1,  9,   2.f/4,5.f/8, 14+subdivide*17,13+subdivide*18,
				 1,-1,-1,  13,  2.f/4,7.f/8, 13+subdivide*19,14+subdivide*18,
				-1,-1,-1,  12,  1.f/4,7.f/8, 13+subdivide*17,14+subdivide*16);
		}

		//efidx and elidx are the first and last index on the edge, i.E. excluding the corner vertice.
		//strippos is 1 for first, 0 for some middle strip, -1 for last. determines whether
		//the first or last index must be duplicated.
		private void genFace(int subdivide, int firstfloatarrayidx, int drawOrderPos,
		                     float x1, float y1, float z1, int cidx1, float s1, float t1, int efidx1, int elidx1,
		                     float x2, float y2, float z2, int cidx2, float s2, float t2, int efidx2, int elidx2,
		                     float x3, float y3, float z3, int cidx3, float s3, float t3, int efidx3, int elidx3,
		                     float x4, float y4, float z4, int cidx4, float s4, float t4, int efidx4, int elidx4) {
			//generate the inner vertices
			//firstfloatarrayidx+y*subdivide+x
			//this assumes a linear face, so coordinates x3,y3,z3,s3,t3 are going to be ignored.
			float dx_x = (x2-x1) / (subdivide+1);
			float dx_y = (x4-x1) / (subdivide+1);
			float dy_x = (y2-y1) / (subdivide+1);
			float dy_y = (y4-y1) / (subdivide+1);
			float dz_x = (z2-z1) / (subdivide+1);
			float dz_y = (z4-z1) / (subdivide+1);
			float ds_x = (s2-s1) / (subdivide+1);
			float ds_y = (s4-s1) / (subdivide+1);
			float dt_x = (t2-t1) / (subdivide+1);
			float dt_y = (t4-t1) / (subdivide+1);
			for(int y = 0; y < subdivide; y++) {
				for(int x = 0; x < subdivide; x++) {
					genVertex(firstfloatarrayidx+y*subdivide+x,
						x1+(x+1)*dx_x+(y+1)*dx_y,
						y1+(x+1)*dy_x+(y+1)*dy_y,
						z1+(x+1)*dz_x+(y+1)*dz_y,
						s1+(x+1)*ds_x+(y+1)*ds_y,
						t1+(x+1)*dt_x+(y+1)*dt_y);
				}
			}

			int estep1 = efidx1 < elidx1 ? 1 : -1;
			int estep2 = efidx2 < elidx2 ? 1 : -1;
			int estep3 = efidx3 < elidx3 ? 1 : -1;
			int estep4 = efidx4 < elidx4 ? 1 : -1;
			/* Using a single tri strip. front face is CCW
			 *     0-2-4
			 *     |/|/|...
			 *     1-3-5
			 *     triangles here are: 0 1 2, 2 1 3, 2 3 4, 4 3 5, ...
			 *
			 *     skipping to next row or similar:
			 *     a-c-d-e
			 *     |/|/|/|
			 *     b-d-e-f
			 *
			 *     this means, the last index of the previous strip is duplicated,
			 *     and then, the first index of the next strip is duplicated.
			 */
			//first row: uses cidx1,  efidx1..elidx1,            cidx2,
			//                elidx4, first ..first+subdivide-1, efidx2
			if (subdivide == 0) {
				if (drawOrderPos != 0) {
					//duplicate the first vertex to complete the skip
					drawOrder[drawOrderPos++] = (short)cidx1;
				}
				drawOrder[drawOrderPos + 0] = (short) cidx1;
				drawOrder[drawOrderPos + 1] = (short) cidx4;
				drawOrder[drawOrderPos + 2] = (short) cidx2;
				drawOrder[drawOrderPos + 3] = (short) cidx3;
				if (drawOrder.length > drawOrderPos + 4) {
					//duplicate the last vertex for skipping, if needed.
					drawOrder[drawOrderPos + 4] = (short) cidx3;
				}
			} else {
				if (drawOrderPos != 0) {
					//duplicate the first vertex to complete the skip
					drawOrder[drawOrderPos++] = (short)cidx1;
				}
				drawOrder[drawOrderPos + 0] = (short) cidx1;
				drawOrder[drawOrderPos + 1] = (short) elidx4;
				for (short x = 0; x < subdivide; x++) {
					drawOrder[drawOrderPos + 2 + x * 2] = (short) (efidx1 + estep1 * x);
					drawOrder[drawOrderPos + 3 + x * 2] = (short) (firstfloatarrayidx + x);
				}
				drawOrder[drawOrderPos + 2 + subdivide * 2] = (short) cidx2;
				drawOrder[drawOrderPos + 3 + subdivide * 2] = (short) efidx2;
				drawOrder[drawOrderPos + 4 + subdivide * 2] = (short) efidx2;//duplicate for next row

				//middle row y=0..subdivide-2:
				//                elidx4-estep4*y     first+subdivide*y     .. first+subdivide*(y+1)-1 efidx2+estep2*y
				//                elidx4-estep4*(y+1) first+subdivide*(y+1) .. first+subdivide*(y+2)-1 efidx2+estep2*(y+1)
				for (int y = 0; y < subdivide - 1; y++) {
					drawOrder[drawOrderPos - 1 + (6 + subdivide * 2) * (y + 1)] = (short) (elidx4 - estep4 * (y + 0));//duplicate first index
					drawOrder[drawOrderPos + 0 + (6 + subdivide * 2) * (y + 1)] = (short) (elidx4 - estep4 * (y + 0));
					drawOrder[drawOrderPos + 1 + (6 + subdivide * 2) * (y + 1)] = (short) (elidx4 - estep4 * (y + 1));
					for (int x = 0; x < subdivide; x++) {
						drawOrder[drawOrderPos + 2 + x * 2 + (6 + subdivide * 2) * (y + 1)] = (short) (firstfloatarrayidx + subdivide * (y + 0) + x);
						drawOrder[drawOrderPos + 3 + x * 2 + (6 + subdivide * 2) * (y + 1)] = (short) (firstfloatarrayidx + subdivide * (y + 1) + x);
					}
					drawOrder[drawOrderPos + 2 + 2 * subdivide + (6 + subdivide * 2) * (y + 1)] = (short) (efidx2 + estep2 * (y + 0));
					drawOrder[drawOrderPos + 3 + 2 * subdivide + (6 + subdivide * 2) * (y + 1)] = (short) (efidx2 + estep2 * (y + 1));
					drawOrder[drawOrderPos + 4 + 2 * subdivide + (6 + subdivide * 2) * (y + 1)] = (short) (efidx2 + estep2 * (y + 1));
				}

				//last row:
				//                elidx4-estep4*(subdivide-1) first+subdivide*(subdivide-1) .. first+subdivide*subdivide-1 efidx2+estep2*(subdivide-1)
				//                cidx4                       elidx3                        .. efidx3                      cidx3
				drawOrder[drawOrderPos - 1 + 6 * subdivide + subdivide * 2 * subdivide] = (short) efidx4;//duplicate first index
				drawOrder[drawOrderPos + 0 + 6 * subdivide + subdivide * 2 * subdivide] = (short) efidx4;
				drawOrder[drawOrderPos + 1 + 6 * subdivide + subdivide * 2 * subdivide] = (short) cidx4;
				for (int x = 0; x < subdivide; x++) {
					drawOrder[drawOrderPos + 2 + x * 2 + 6 * subdivide + 2 * subdivide * subdivide] = (short) (firstfloatarrayidx + subdivide * (subdivide - 1) + x);
					drawOrder[drawOrderPos + 3 + x * 2 + 6 * subdivide + 2 * subdivide * subdivide] = (short) (elidx3 - estep3 * x);
				}
				drawOrder[drawOrderPos + 2 + 8 * subdivide + 2 * subdivide * subdivide] = (short) elidx2;
				drawOrder[drawOrderPos + 3 + 8 * subdivide + 2 * subdivide * subdivide] = (short) cidx3;
				if (drawOrder.length > drawOrderPos + 4 + 8 * subdivide + 2 * subdivide * subdivide) {
					//duplicate the last vertex for skipping, if needed.
					drawOrder[drawOrderPos + 4 + 8 * subdivide + 2 * subdivide * subdivide] = (short) cidx3;
				}
			}
		}

		//idx1 and idx2 are the first resp last index _on the edge_, i.E. away from the
		//corner vertices.
		private void genEdge(float x1, float y1, float z1, float s1, float t1, int idx1,
		                     float x2, float y2, float z2, float s2, float t2, int idx2) {
			int subedgecount = idx2-idx1+(1+2-1);
			float dx = (x2-x1)/subedgecount;
			float dy = (y2-y1)/subedgecount;
			float dz = (z2-z1)/subedgecount;
			float ds = (s2-s1)/subedgecount;
			float dt = (t2-t1)/subedgecount;
			float x = x1+dx;
			float y = y1+dy;
			float z = z1+dz;
			float s = s1+ds;
			float t = t1+dt;
			for(int idx = idx1; idx <= idx2; idx++) {
				genVertex(idx, x,y,z,s,t);
				x += dx;
				y += dy;
				z += dz;
				s += ds;
				t += dt;
			}
		}

		private void genVertex(int idx, float x, float y, float z, float s, float t) {
			float d = (float)java.lang.Math.sqrt(x*x+y*y+z*z);
			floatAttribs[idx*FLOATSTRIDE+0] = x/d;
			floatAttribs[idx*FLOATSTRIDE+1] = y/d;
			floatAttribs[idx*FLOATSTRIDE+2] = z/d;
			floatAttribs[idx*FLOATSTRIDE+3] = s;
			floatAttribs[idx*FLOATSTRIDE+4] = t;
		}

		Sphere() {
			genVerticesAndIndexes(10);

			GLES20.glGenBuffers(1, vtxbo, 0);
			GLES20.glGenBuffers(1, idxbo, 0);
			// initialize vertex byte buffer for shape coordinates
			ByteBuffer bb = ByteBuffer.allocateDirect(
				// (# of coordinate values * 4 bytes per float)
				floatAttribs.length * 4);
			bb.order(ByteOrder.nativeOrder());
			floatBuffer = bb.asFloatBuffer();
			floatBuffer.put(floatAttribs);
			floatBuffer.position(0);

			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vtxbo[0]);
			GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
				floatBuffer.capacity()*4, floatBuffer,GLES20.GL_STATIC_DRAW);
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

			// initialize byte buffer for the draw list
			ByteBuffer dlb = ByteBuffer.allocateDirect(
				// (# of coordinate values * 2 bytes per short)
				drawOrder.length * 2);
			dlb.order(ByteOrder.nativeOrder());
			drawListBuffer = dlb.asShortBuffer();
			drawListBuffer.put(drawOrder);
			drawListBuffer.position(0);

			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, idxbo[0]);
			GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER,
				drawListBuffer.capacity()*2,drawListBuffer,GLES20.GL_STATIC_DRAW);
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

			int vertexShader = MyGLRenderer.loadShader(GLES20.GL_VERTEX_SHADER,
				vertexShaderCode);
			int fragmentShader = MyGLRenderer.loadShader(GLES20.GL_FRAGMENT_SHADER,
				fragmentShaderCode);

			// create empty OpenGL ES Program
			mProgram = GLES20.glCreateProgram();

			// add the vertex shader to program
			GLES20.glAttachShader(mProgram, vertexShader);

			// add the fragment shader to program
			GLES20.glAttachShader(mProgram, fragmentShader);

			// creates OpenGL ES program executables
			GLES20.glLinkProgram(mProgram);

			// get handle to vertex shader's vPosition member
			maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
			// get handle to vertex shader's aTexCoord member
			maTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
			// get handle to shape's transformation matrix
			muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
			// get handle to fragment shader's uTexture member
			muTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
		}

		void loadTexture(Context context, int resourceId) {
			GLES20.glGenTextures(1,texHandle, 0);
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inScaled = false;
			Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resourceId, options);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHandle[0]);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
			GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);
			bitmap.recycle();
		}

		void draw(float[] mvpMatrix) {
			// Add program to OpenGL ES environment
			GLES20.glUseProgram(mProgram);

			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vtxbo[0]);

			// Prepare the triangle position data
			GLES20.glVertexAttribPointer(maPositionHandle, 3,
				GLES20.GL_FLOAT, false,
				FLOATSTRIDE*4, 0);
			// Enable a handle to the triangle vertices
			GLES20.glEnableVertexAttribArray(maPositionHandle);

			// Prepare the triangle texture coordinate data
			GLES20.glVertexAttribPointer(maTexCoordHandle, 2,
				GLES20.GL_FLOAT, false,
				FLOATSTRIDE*4, 3*4);
			// Enable a handle to the triangle vertices
			GLES20.glEnableVertexAttribArray(maTexCoordHandle);

			// Pass the projection and view transformation to the shader
			GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0);

			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, idxbo[0]);

			//make texture unit 0 active
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
			//bind our texture to unit 0
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHandle[0]);
			//map muTextureHandle to use unit 0
			GLES20.glUniform1i(muTextureHandle, 0);

			// Draw the triangle
			GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, 0);

			// Unbind
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

			// Disable vertex array
			GLES20.glDisableVertexAttribArray(maPositionHandle);
			// Disable vertex array
			GLES20.glDisableVertexAttribArray(maTexCoordHandle);
		}
	}


	private Sphere mNavball;
	// mMVPMatrix is an abbreviation for "Model View Projection Matrix"
	private final float[] mMVPMatrix = new float[16];
	private final float[] mProjectionMatrix = new float[16];
	private final float[] mViewMatrix = new float[16];
	private float[] mRotationMatrix = {1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
	public volatile float mAngle;
	private Context mActivityContext;
	private int mRotation = Surface.ROTATION_0;

	MyGLRenderer(Context activityContext)
	{
		mActivityContext = activityContext;
	}

	public void onSurfaceCreated(GL10 unused, EGLConfig config) {
		// Set the background frame color
		GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		GLES20.glClearDepthf(1.0f);

		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthFunc( GLES20.GL_LEQUAL );
		GLES20.glDepthMask( true );

		// initialize a square
		mNavball = new Sphere();

		mNavball.loadTexture(mActivityContext, R.drawable.navball);

		// Set the camera position (View matrix)
		// Positions the camera at 0,0,3, looking at 0,0,0, up pointing to 0,1,0
		Matrix.setLookAtM(mViewMatrix, 0, 0, 0, 10, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
	}

	private final float[] scratch = new float[16];

	public void onDrawFrame(GL10 unused) {

		// Redraw background color
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);

		// Calculate the projection and view transformation
		Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);

		// Combine the rotation matrix with the projection and camera view
		// Note that the mMVPMatrix factor *must be first* in order
		// for the matrix multiplication product to be correct.
		Matrix.multiplyMM(scratch, 0, mMVPMatrix, 0, mRotationMatrix, 0);

		// Draw shape
//		mTriangle.draw(scratch);
		mNavball.draw(scratch);
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int width, int height) {
		GLES20.glViewport(0, 0, width, height);

		float ratio = (float) width / height;
		float hor = ratio;
		float ver = 1;
		if (hor > 1) {
			hor = 1;
			ver = 1/ratio;
		}

		// this projection matrix is applied to object coordinates
		// in the onDrawFrame() method
		Matrix.frustumM(mProjectionMatrix, 0, -hor, hor, -ver, ver, 5, 15);
	}

	private final float[] scratch2 = new float[16];
	private final float[] scratch3 = new float[16];
	void setTransform(float[] matrix) {
		/* View coordinate system: X right, Y up, Z to viewer
		   Ball coordinate system: X 90°, Y up, Z 0°
		   Sensor coordinate system: X right, Y up, Z to viewer(out of the display)
		 */
		//rotate for screen orientation
		if (mRotation == Surface.ROTATION_0) {
			Matrix.setIdentityM(scratch2, 0);
		} else if (mRotation == Surface.ROTATION_90) {
			Matrix.setRotateM(scratch2, 0, 90f, 0, 0, 1);
		} else if (mRotation == Surface.ROTATION_180) {
			Matrix.setRotateM(scratch2, 0, 180f, 0, 0, 1);
		} else if (mRotation == Surface.ROTATION_270) {
			Matrix.setRotateM(scratch2, 0, 270f, 0, 0, 1);
		}
		//rotate for "world" rotation
		Matrix.multiplyMM(scratch3, 0, scratch2, 0, matrix, 0);
		//rotate to get the correct ball orientation. we could bake this into the spheres coordinates.
		Matrix.rotateM(scratch3, 0, 180f, 0, 0, 1);
		Matrix.rotateM(scratch3, 0, 90f, 1, 0, 0);
		System.arraycopy(scratch3, 0, mRotationMatrix, 0, mRotationMatrix.length);
	}

	void setScreenRotation(int rotation) {
		mRotation = rotation;
	}
}
